# Nisaba — privacy policy

*Last updated: 16 October 2026. Draft for the Play listing: fill in the contact line before publishing.*

Nisaba is a handwriting notebook. You write; after a pause, an AI model writes an answer back on the page. This page
says exactly what leaves your device, where it goes, and what stays.

## What stays on your device

- Your notebooks: pages, ink, imported PDFs, answers, lesson progress, reminders you accepted.
- Nisaba's memory of you (short facts it noted from your notebooks), which you can read and delete in
  Settings → Memory, and which private notebooks neither add to nor see.
- Your settings and your DeepSeek API key. The key is encrypted with the device keystore and never leaves the device,
  except inside requests to DeepSeek (below).

Nisaba has no account, no server of its own, no analytics, no advertising and no crash reporting.

## What leaves your device, and only when

Only when Nisaba answers (after you pause, tap Ask now, or choose an action such as Check my work), it sends one
request to DeepSeek (`api.deepseek.com`), authorised with **your own** API key:

- a picture of the current page (and a closer picture of what you just wrote), including any PDF page under it;
- the recent questions and answers of that notebook;
- the text of the notebook's PDF, if it has one (the pages near where you are);
- the facts in Nisaba's memory of you (not from or into private notebooks);
- the voice's instructions, and the date and time (so "remind me tomorrow" can be understood).

DeepSeek processes these to produce the answer under its own terms and privacy policy. Nisaba sends nothing else to
anyone. Drafting "a voice of your own" sends only the description you typed.

## What you choose to send elsewhere

- **Exports** (a notebook as PDF or text) and **backups** go only to the file or folder you pick in the system's file
  dialog. A backup contains your notebooks, ink, PDFs, settings and memory, but not your API key.
- **Reminders** you accept are handed to your calendar app, or kept by Nisaba and shown as a notification.
- **Copy** and **Report** on an answer use the clipboard and the system share sheet, to wherever you send them.

## Permissions

- *Internet* and *network state*: to reach DeepSeek, and to retry a question once the connection is back.
- *Notifications* (asked the first time you add a reminder without a calendar app): to remind you.

## Deleting your data

Delete a notebook (it waits 30 days in the trash, then is deleted for good; or empty the trash), forget memory facts in
Settings → Memory, or uninstall Nisaba to remove everything it stored. Requests already processed by DeepSeek are
subject to DeepSeek's retention policy.

## Children

Nisaba is not directed at children under 13.

## Contact

Questions about this policy: *[your e-mail address here]*.
