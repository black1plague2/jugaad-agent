# Privacy and data handling

Jugaad Agent runs entirely on the phone. There is no cloud, no server and no account.

## What the app records

- Microphone: three-second clips while the phone rests on a machine housing, only when the
  technician starts a reference measurement or a reading. Clips may pick up nearby speech.
- Accelerometer, gyroscope and magnetometer over the same three seconds.
- Camera: an optional photo when creating a piece of equipment.
- Equipment names, machine type, labels the technician confirms, reading results.

## What the app keeps

- Raw audio is processed in memory and discarded. The app stores a 260-value feature vector
  per clip (256 log-mel statistics plus four sensor indices), which does not reconstruct speech.
- Spectrogram thumbnails, scores, labels and equipment records stay in the app's private storage.
- Android cloud backup and device-to-device transfer are disabled (`allowBackup="false"`).

## What leaves the phone

- Only to other phones running Jugaad Agent on the same WiFi or WiFi Direct group, and only
  when a phone joins a federated network: classifier weights, network standings, and, when sample
  sharing is enabled in Group settings, feature vectors with their labels. Never audio, photos
  or equipment names.
- Equipment marked "Bench / test equipment" is excluded from training and sharing.
- The sync link is currently unauthenticated and unencrypted. Use it only on a trusted plant
  network or a WiFi Direct group you created.

## Your controls

- Microphone and camera permissions can be revoked in Android settings at any time.
- Sample sharing and auto-train are switches in Group settings.
- Uninstalling the app deletes everything it stored.

## Legal notes

The operator deploying the app is the data fiduciary or controller under the Digital Personal
Data Protection Act 2023 (India) or GDPR (EU), and should inform staff that readings may capture
ambient sound. Contact for privacy questions: the maintainer listed in the repository.
