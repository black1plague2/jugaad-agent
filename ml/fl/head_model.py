"""
Trainable FL head: Dense(257->h1, relu) -> [Dense(h1->h2, relu) ->] Dense(h_last->3) -> softmax.

Matches "Model spec v2" in plans/2026-09-12-champion-challenger-network.md (which
generalises the v1 shared contract in plans/2026-09-12-federated-on-device-learning.md):
- weight layout: per layer concat(W.flatten(), b), row-major, concatenated in layer order
- fixed seed 7 so all phones/variants start from identical weights for a given architecture
- four signatures: infer, train, get_weights, set_weights (fixed batch, float32 only)

`layers` is the full dims list, e.g. [260, 64, 3] (base/small) or [260, 64, 32, 3] (deep).
INPUT_DIM (260) and N_CLASSES (3) are fixed across every variant; only the hidden
dims vary, so infer/train keep a static input_signature while get_weights/set_weights
size themselves to the instance's own weight count.

`train` applies SGD with weight decay (`w -= lr * (grad + WEIGHT_DECAY * w)`) to
every trainable variable (weights and biases) -- see "Training honesty" in
plans/2026-09-12-sensors-strategies-datasets.md.
"""
import numpy as np
import tensorflow as tf

INPUT_DIM = 260
N_CLASSES = 3
TRAIN_BATCH = 8
LR = 0.05
WEIGHT_DECAY = 1e-4
SEED = 7


def compute_weight_count(layers):
    """Sum of W (layers[i] x layers[i+1]) + b (layers[i+1]) over every layer."""
    total = 0
    for fan_in, fan_out in zip(layers[:-1], layers[1:]):
        total += fan_in * fan_out + fan_out
    return total


class FlHead(tf.Module):
    """N-layer MLP head with train/infer/get_weights/set_weights TFLite signatures."""

    def __init__(self, layers, name=None, init_weights=None):
        """
        init_weights: optional flat float32 vector (same layout as
        get_weights()/set_weights()) to bake pretrained/fine-tuned weights into
        a freshly exported graph instead of the seed-7 random init. Used by
        pretrain.py to ship trained weights in the exported .tflite files
        while keeping the train/infer/get_weights/set_weights signatures
        identical. Falls back to the seed-7 random init when omitted (or
        when a caller has no trained weights for a variant).
        """
        super().__init__(name=name)
        assert layers[0] == INPUT_DIM and layers[-1] == N_CLASSES, layers
        self.layers_spec = list(layers)
        self.weight_count = compute_weight_count(self.layers_spec)
        if init_weights is not None:
            assert len(init_weights) == self.weight_count, (
                f"init_weights length {len(init_weights)} != {self.weight_count}"
            )

        # Glorot-uniform for weights, zeros for biases; one seeded generator
        # sequence drawn in layer order (biases don't consume a draw), same as v1.
        rng = np.random.RandomState(SEED)
        self.Ws = []
        self.bs = []
        offset = 0
        for i, (fan_in, fan_out) in enumerate(zip(self.layers_spec[:-1], self.layers_spec[1:])):
            limit = np.sqrt(6.0 / (fan_in + fan_out))
            w_init = rng.uniform(-limit, limit, size=(fan_in, fan_out)).astype(np.float32)
            b_init = np.zeros([fan_out], dtype=np.float32)
            if init_weights is not None:
                n = fan_in * fan_out
                w_init = np.asarray(init_weights[offset:offset + n], dtype=np.float32).reshape(fan_in, fan_out)
                offset += n
                b_init = np.asarray(init_weights[offset:offset + fan_out], dtype=np.float32)
                offset += fan_out
            self.Ws.append(tf.Variable(w_init, name=f"W{i + 1}", dtype=tf.float32))
            self.bs.append(tf.Variable(b_init, name=f"b{i + 1}"))

        # set_weights' input shape depends on this instance's weight_count, so it
        # can't be a class-level @tf.function decorator (that's fixed at class-body
        # evaluation time, before any instance/architecture exists).
        self.set_weights = tf.function(
            self._set_weights_impl,
            input_signature=[tf.TensorSpec([self.weight_count], tf.float32)],
        )

    def _forward(self, x):
        h = x
        n = len(self.Ws)
        for i, (W, b) in enumerate(zip(self.Ws, self.bs)):
            z = tf.matmul(h, W) + b
            # tf.maximum(z, 0.0) instead of tf.nn.relu(z): its gradient decomposes
            # into GreaterEqual/SelectV2 (TFLite builtins) instead of the fused
            # ReluGrad op, which TFLITE_BUILTINS-only conversion cannot represent.
            h = tf.maximum(z, 0.0) if i < n - 1 else z  # no activation on the logits
        return h

    @tf.function(input_signature=[tf.TensorSpec([1, INPUT_DIM], tf.float32)])
    def infer(self, x):
        logits = self._forward(x)
        probs = tf.nn.softmax(logits, axis=-1)
        return {"probs": probs, "logits": logits}

    @tf.function(input_signature=[
        tf.TensorSpec([TRAIN_BATCH, INPUT_DIM], tf.float32),
        tf.TensorSpec([TRAIN_BATCH, N_CLASSES], tf.float32),
    ])
    def train(self, x, y):
        with tf.GradientTape() as tape:
            logits = self._forward(x)
            loss = tf.reduce_mean(
                tf.nn.softmax_cross_entropy_with_logits(labels=y, logits=logits)
            )
        variables = self.Ws + self.bs
        grads = tape.gradient(loss, variables)
        for v, g in zip(variables, grads):
            v.assign_sub(LR * (g + WEIGHT_DECAY * v))
        return {"loss": tf.reshape(loss, [1])}

    @tf.function(input_signature=[tf.TensorSpec([1], tf.float32)])
    def get_weights(self, dummy):
        del dummy
        parts = []
        for W, b in zip(self.Ws, self.bs):
            parts.append(tf.reshape(W, [-1]))
            parts.append(tf.reshape(b, [-1]))
        return {"w": tf.concat(parts, axis=0)}

    def _set_weights_impl(self, w):
        i = 0
        for W, b in zip(self.Ws, self.bs):
            n = int(np.prod(W.shape))
            W.assign(tf.reshape(w[i:i + n], W.shape))
            i += n
            n = int(b.shape[0])
            b.assign(tf.reshape(w[i:i + n], b.shape))
            i += n
        return {"ok": tf.constant([1.0], dtype=tf.float32)}


if __name__ == "__main__":
    for layers in ([260, 64, 3], [260, 32, 3], [260, 64, 32, 3]):
        head = FlHead(layers)
        n_params = sum(int(np.prod(v.shape)) for v in head.Ws + head.bs)
        print(layers, "param count:", n_params, "expected:", compute_weight_count(layers))
