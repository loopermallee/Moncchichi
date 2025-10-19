# Nullability Audit

| Location | Details | Resolution |
| --- | --- | --- |
| `hub/src/main/java/com/loopermallee/moncchichi/hub/model/Repository.kt:L14-L53` | `service` was treated as always non-null even before the hub bound to `G1ServiceManager`, risking runtime crashes. | Track the binding with a nullable backing field and expose a `boundService` accessor that fails fast when used before `bindService()`, ensuring every call path performs an explicit null contract check. |
| `client/src/main/java/io/texne/g1/basis/client/G1ServiceCommon.kt:L23-L29` | `Glasses.batteryPercentage` was modelled as a mandatory `Int` even though the AIDL contract allows it to be absent (`-1`). | Made the property nullable with documentation to align the Kotlin model with the parcelable definition. |
| `client/src/main/java/io/texne/g1/basis/client/G1ServiceManager.kt:L53-L60` | The discovery flow injected the sentinel value `-1` for missing battery data, which downstream code could mistake for a real percentage. | Populate Kotlin callers with `null` whenever the service reports an unknown percentage to avoid unsafe assumptions. |
| `client/src/main/java/io/texne/g1/basis/client/G1ServiceClient.kt:L62-L68` | Observed glasses passed the raw `Int` battery reading, so `-1` slipped through to UI code without a safety check. | Convert the value with `takeIf { it >= 0 }` so unknown readings become `null` and trigger explicit handling. |
| `subtitles/src/main/java/io/texne/g1/subtitles/ui/SubtitlesScreen.kt:L135-L144` | The subtitles UI interpolated `glasses.batteryPercentage` directly, which would crash once the property became nullable. | Cached the battery value in a local, derived a friendly label, and branched colours to account for the `null` case. |

_No additional unsafe nullable usages were detected in `hub/src/main/java/com/loopermallee/moncchichi/hub/ui/ApplicationFrame.kt`; the screen now relies on local, null-checked copies of view-model state._
