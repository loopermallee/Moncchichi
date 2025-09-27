# G1 Basis
Open, multipurpose infrastructure for writing Android applications that talk to the [Even Realities G1](https://www.evenrealities.com/g1) glasses.

## 🚧 Current Goals & Progress

| Goal | Description | Progress |
|------|-------------|----------|
| **Null-Safety Clean-Up** | Clean up all Kotlin nullability errors (smart-cast issues like `state.connectedGlasses`) by introducing safe locals and explicit null checks. | ✅ 80% complete (remaining: `hub/ApplicationFrame.kt` UI bindings) |
| **Basic Glasses Pairing Workflow** | Provide a minimal hub screen to discover glasses and initiate pairing via the existing service layer. | 🔄 50% complete |
| **AIDL Alignment** | Ensure all AIDL parcelables (e.g. `G1Glasses`, `G1ServiceState`) map correctly to Kotlin data classes with matching nullability. | 🔄 60% complete |
| **Stable BLE Pairing** | Refactor `G1BLEManager`/`G1Connector` for stable single-session connection (no redundant reconnect). | 🔄 40% complete |
| **Debug Build** | Establish working `assembleDebug` pipeline to generate downloadable APK. | ✅ 100% complete |
| **Material Components** | Standardize UI components on Material 1.12.0 and CardView 1.0.0. | 🔄 30% complete |

---

## 📝 Planned Functions & Features

| Feature | Description | Status |
|---------|-------------|--------|
| **Pairing Wizard** | Simple first-run wizard to discover and pair glasses (no theme yet). | ✅ Basic workflow available |
| **Battery & Connection UI** | Live display of battery % and connection state from `G1Glasses`. | ⏳ Planned |
| **Text Page Display** | Use `displayTextPage()` AIDL to send multi-page text to glasses. | ⏳ Planned |
| **Stop Displaying** | Implement `stopDisplaying()` AIDL to cancel display on glasses. | ⏳ Planned |
| **Heartbeat Monitoring** | Maintain 0x25 heartbeat automatically for stable connection. | ⏳ Planned |
| **Logging / Telemetry Toggle** | Allow user to enable or disable telemetry events for debugging. | ⏳ Planned |

---

### Next Milestones

1. Finish null-safety fixes in `hub` module.
2. Confirm `G1BLEManager` hand-off logic with a real device.
3. Generate fresh debug APK after each merge for testing.
4. Begin implementing the “Pairing Wizard” UI.

## Core
The **core** module contains the source code to the core library. 
This library allows for interfacing directly with the glasses through a simple abstraction that uses modern Android and Kotlin features like coroutines and Flow. 
When using this library directly, only one application can connect to and interface with the glasses at one time. The basis core library is used under the hood by the **service**.

*(more details coming soon)*

## Service
The **service** module implements a shared Android service that multiple applications can use to interface simultaneously with the glasses.  
Applications interface with the service using the AIDL-defined generated code and a simple wrapper that exposes the service using coroutines and flow the way the Core library does.
The service also handles requesting the necessary permissions at runtime, so calling applications do not have to.

### Releases

The latest release is available on [Maven Central](https://central.sonatype.com/artifact/io.texne.g1.basis/service)

```kotlin
implementation("io.texne.g1.basis:service:1.1.0")
```

## AIDL
The **aidl** module contains the specification of the RPC protocol between the client and service.  
It is the glue that makes it possible for multiple apps using the client to share the service (and access to the glasses).
Both the **client** and **service** module require it, but you would not need to include it or use it directly.

## Hub

The **hub** module contains the application for running the G1 service and managing connections to glasses on Android.
The application can seamlessly and reliably discover, connect and disconnect, and send commands to glasses.
It must be running on your phone and connected to the glasses for any apps using the client library to talk to them.

*(more details coming soon)*

## Client
The **client** module implements a simple native interface to the shared service.

### Releases

The latest release is available on [Maven Central](https://central.sonatype.com/artifact/io.texne.g1.basis/client)

```kotlin
implementation("io.texne.g1.basis:client:1.1.0")
```

### 1. Initialization

Initialize the client by calling

```kotlin
val client = G1ServiceClient.open(applicationContext)
if(client == null) {
    // ERROR
}
```

This starts the service (if it is not already running) and connects to it.
Similarly, when you do not need to use the service anymore, call

```kotlin
client.close()
```

In the example application (a single-activity Compose app) open() is called in the activity's onCreate() and close() is called in the onDestroy().

### 2. Service State

The client exposes its state through

```kotlin
client.state
```

client.state is a StateFlow that is null if the service is initalizing, or type 

```kotlin
data class G1ServiceState(
  val status: Int, 
    // values can be
    //    G1ServiceState.READY - the service is initialized and ready to scan for glasses
    //    G1ServiceState.LOOKING - the service is scanning for glasses
    //    G1ServiceState.LOOKED - the service has looked for glasses and is ready to look again
    //    G1ServiceState.ERROR - the service encountered an error looking for glasses
  val glasses: Array<G1Glasses>
    // glasses that the service has found
)
```

```kotlin
data class G1Glasses(
  val id: String,
    // unique id for glasses, treat as opaque (constructed from device MAC)
  val name: String,
    // label for glasses
  val connectionState: Int,
    // values can be
    //    G1ServiceState.UNINITIALIZED - the service is just setting the glasses up to connect
    //    G1ServiceState.DISCONNECTED - the glasses are not connected
    //    G1ServiceState.CONNECTING - the service is connecting to the glasses
    //    G1ServiceState.CONNECTED - the glasses are ready to use
    //    G1ServiceState.DISCONNECTING - the service is disconnecting from the glasses
    //    G1ServiceState.ERROR - an error ocurred while setting up or connecting the glasses
  val batteryPercentage: Int?,
    // the percentage battery left of the side that has the least left, or null if unknown
)
```

### 3. Scanning for Glasses (FOR MANAGING APPS)

To start scanning for glasses, call 

```kotlin
client.lookForGlasses()
```

The function will scan for glasses for 15 seconds. The client.state flow will update as changes occur. 

### 4. Connecting and Disconnecting (FOR MANAGING APPS)

To connect to a pair of glasses, call the suspend function

```kotlin
val success = client.connect(id)
```

in a coroutine scope, using the id of the glasses you want to connect.  
The client.state will update as changes in connection state of the glasses occur.
Similarly, to disconnect, invoke

```kotlin
client.disconnect(id)
```

### 5. Listing Connected Glasses

If an app wishes to only use connected glasses, and not manage them, it can just call this to list the currently connected Glasses.

```kotlin
val glasses = client.listConnectedGlasses()
```

It returns a List<Glasses> or null if the service is not initialized or reachable

### 6. Displaying Unformatted Text

The basic facility to display text immediately is the suspend function

```kotlin
val success = client.displayTextPage(id, page)
```

where id is the glasses id, and page is a list of a maximum of five strings of a maximum 40-character width each.
This call displays the text immediately as it is formatted, and leaves it on the display.

To stop displaying text, call

```kotlin
val success = client.stopDisplaying(id)
```

this clears the text and goes back to the previous context (screen off, dashboard).
To automatically remove the text after a given amount of time, call

```kotlin
val success = client.displayTimedTextPage(id, page, milliseconds)
```

where milliseconds is how long to display the text before it disappears.

### 7. Displaying Formatted Text

The client includes convenience methods to format text automatically for the display.
You can call

```kotlin
val success = client.displayFormattedPage(id, formattedPage)
```

where

```kotlin
data class FormattedPage(
    val lines: List<FormattedLine>,
    val justify: JustifyPage
)

enum class JustifyPage { TOP, BOTTOM, CENTER }

data class FormattedLine(
    val text: String,
    val justify: JustifyLine
)

enum class JustifyLine { LEFT, RIGHT, CENTER }
```

this allows you to center text horizontally and vertically. 
This method displays text immediately and indefinitely until it is removed with client.stopDisplaying().

You can also have it automatically removed with

```kotlin
val success = client.displayTimedFormattedPage(id, timedFormattedPage)
```

where

```kotlin
data class TimedFormattedPage(
    val page: FormattedPage,
    val milliseconds: Long
)
```

### 8. Displaying Formatted Page Sequences

You can also display a sequence of formatted pages, each with its own duration:

```kotlin
val success = client.displayFormattedPageSequence(id, sequence)
```

where sequence is a list of TimedFormattedPage.

### 9. Quick Convenience Centered Text Timed Display

Finally, the convenience method:

```kotlin
val success = client.displayCentered(id, textLines, milliseconds)
```

displays the list of strings (maximum five strings of 40 characters limit each) centered on the screen for the duration of milliseconds, or permanently if null.
The default for milliseconds is 2000.

## Subtitles

The **subtitles** module contains an example application that uses the client to transcribe everything it hears and send it 
to the glasses that are connected using the Hub.  It is in progress, it does not yet work.

*(more details coming soon)*
