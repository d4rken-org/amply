# Play Console — Data safety

Form: Play Console → App content → **Data safety**

Source of truth for these answers: [`PRIVACY_POLICY.md`](../../PRIVACY_POLICY.md) (published at
<https://amply.darken.eu/privacy>). If one changes, change both.

## Answers

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | n/a (nothing collected) |
| Do you provide a way for users to request that their data is deleted? | n/a (nothing collected) |
| Does your app contain ads? | **No** |
| Does your app have in-app purchases? | **Yes** (see below) |
| Data used for tracking / advertising | **None** |

Privacy policy URL: `https://amply.darken.eu/privacy`

## In-app purchases

The Play build sells an optional upgrade — a yearly subscription (with a free trial) and an
equivalent one-time purchase — that unlocks the charge history, the home-screen widget and the Quick
Settings tile. Charge control itself is free.

This does not change the "no data collected" answer above. Purchases are processed entirely by
Google Play; Amply never sees payment details and has no server to send them to. What it keeps
locally is the entitlement bookkeeping — when Google Play last confirmed a purchase and which
product it was — so the upgrade survives a Play outage. That never leaves the device.

The FOSS build contains no billing code at all: its unlock is a local record written after a visit
to the GitHub Sponsors page.

## Why "no data collected" is the correct answer

Play defines collection as transmitting data off the device. Amply transmits nothing on its own:
no analytics SDK, no crash reporter, no advertising ID, no account system, no network calls in the
normal running of the app. Everything it reads — battery level, temperature, voltage, current,
charge cycles, charger state, the charge-protection setting — is read from the system and stays on
the device. The opt-in charging history is stored in Amply's private app database and is deleted by
its own retention window; it is never uploaded.

Three features move data off the device, and all three are exempt because they happen **only at the
user's explicit direction, to a destination the user picks**:

1. **Device support report** — built only when the user asks for it, shown in full before it leaves
   the app, with common identifiers redacted. The user then chooses to open a prefilled GitHub
   issue page or to hand the text to an email app.
2. **Debug log recording** — off by default, starts only after an explicit confirmation, stays in
   private app storage, and leaves only through Android's share sheet if the user shares it.
3. **Charge-history export / share** — the user's own data, shared by the user, to a target they
   choose.

None of these is a developer-operated collection endpoint. Nothing goes to the developer's servers,
because there are none.

## The judgement call, stated plainly

If Google reads the GitHub-issue path as developer-directed sharing rather than user-directed, the
honest correction is to declare **"App info and performance → Other app performance data"** as
*shared, optional, for app functionality (support)*. The user-initiated exemption is the better fit
and is what this form answers, but the fallback exists if review pushes back — do not argue the
point, just amend the form.
