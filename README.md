# Lining up 🌙

A tiny Android app for two. Each evening at 8pm a popup asks ✅ or ❌, then
🍆 or 👅. Your answer shows on your partner's phone with the time you tapped
it, and the day resets at 2am.

## Install

On each phone, tap
**https://github.com/jollydoddger/lining-up/releases/download/latest/lining-up.apk**
and allow installing from your browser when Android asks. Updates install the
same way, over the top.

## Pair

1. One of you opens the app and taps **✨ Start**. A share sheet opens with a
   code. Send it to your partner however you like.
2. The other copies the code, opens the app, taps **📋 Paste** then **💞 Join**.

That's it. Pick a face for yourself under ⚙️.

## Using it

- ✅ / ❌ — tap to answer, tap again to take it back.
- 🍆 / 👅 — appear once you've said ✅.
- Top card — your partner's answer and the time they tapped it. ⏳ means
  nothing yet today.
- 💞 — both of you said ✅. Both phones get a quiet notification the first
  time it happens each day.
- ⚙️ — your face, the popup time (🔔, default 20:00, switch it off if you
  like), the pairing code (📤 to send it again, 🔓 then 💔 to unpair), and
  📡 the last time it heard from the relay.

When your partner changes their answer, you get a notification within about
15 minutes, or within 20 seconds if the app is open.

## Privacy

Answers travel through [ntfy.sh](https://ntfy.sh), a free public relay, so
there's no account and no server to run. The pairing code is never sent:
the relay sees a topic name hashed from the code and messages sealed with
AES-256-GCM using a key derived from the code. Anyone without the code sees
random bytes. The relay keeps messages for 12 hours, so each phone resends
the day's answer every few hours to keep it there until 2am.

Notifications contain only emoji, and if your phone hides sensitive
notification content on the lock screen they show only 🌙.

## Building

CI builds every push and publishes the APK from the default branch.
`tools/verify.sh` compiles and tests the pure logic (`Core.kt`) without the
Android SDK.
