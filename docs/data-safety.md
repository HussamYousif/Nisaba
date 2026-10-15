# Play Console — data safety answers (draft)

Nisaba has no backend. Everything below is about what the app sends to **DeepSeek**, a service the person configures
with their own API key, at the moment they ask for an answer.

| Question | Answer | Why |
|---|---|---|
| Does the app collect or share user data? | **Yes, shared** | Page images, notebook text and memory facts go to DeepSeek to produce answers. |
| Is data encrypted in transit? | **Yes** | HTTPS only (`https://api.deepseek.com`; cleartext traffic is off, the Android 9+ default). |
| Can users request deletion? | **Yes** | Everything is local: delete notebooks / memory, or uninstall. DeepSeek retention: its own policy. |
| Data types shared | **Photos and videos → Photos** (page images); **Messages → Other in-app messages** (questions and answers); **Files and docs** (PDF text); **Personal info → Other** (memory facts the person wrote about themselves) | Sent only when an answer is requested; processed, not stored by Nisaba. |
| Purpose | **App functionality** | Producing the handwritten answer. |
| Optional? | **Yes** | Without a key, or with listening off and Ask now unused, nothing is sent. |
| Collected (stored off-device by the developer)? | **No** | No servers, analytics, ads or crash reporting. |

Before submitting, also: point the listing's privacy policy URL at a hosted copy of `docs/privacy.md`; confirm the
Onyx SDK licence allows redistribution; release builds hide the two character homages (Yukino, Zagreus).
