# CONTEXT_ENGINEERING.md  
**Phase 4.6 — BLE Workflow Realignment & Case Telemetry Intelligence**

---

## 1. CONTEXT OVERVIEW

Recent diagnostics (2025-11-11) compared the **Moncchichi Hub** app against the official **Even Reality v1.6.6** firmware and companion app.  
The testing covered multiple device states:
- Lid open / closed  
- Charging / non-charging  
- In-case / out-of-case / worn  
- Widgets (GPS → News → Calendar)  
- Gesture tests (single / double / hold)

The Even Reality app demonstrated stable and stateful BLE telemetry, while Moncchichi Hub displayed transient errors and misclassified events.

---

## 2. SUMMARY OF FINDINGS

| Category | Even Reality Behavior | Moncchichi Hub Behavior | Issue Summary |
|-----------|----------------------|--------------------------|---------------|
| **Event Parsing** | Distinguishes gesture (0x00–0x05) vs system (0x06–0x0F). | Treats all 0xF5 frames as gestures. | Causes “Gesture Left Unknown” spam when idle or docked. |
| **Case Telemetry** | Parses 0x0E / 0x0F and 0x2C for lid + battery status. | Missing implementation. | Case battery % and lid open state absent. |
| **Heartbeat (0x25)** | Sent every ≈30 s to each lens individually. | Irregular / shared timer. | Causes → PING ← ERR and dropouts after ~30 s. |
| **ACK Handling** | Maps 0xC9=OK  0xCA=BUSY  0xCB=CONTINUE  0xC0=COMPLETE. | BUSY misread as fail / CONTINUE ignored. | False error messages, premature retries. |
| **Reconnect Logic** | Independent retry loops per lens. | Single global retry attempt. | One lens can fail silently. |
| **Telemetry State** | Maintains context (wear, case, charging). | Stateless per-packet. | Logs unfiltered noise and duplicate events. |
| **UI Binding** | Displays case battery, lid status, lens connection state. | Limited to lens battery only. | Incomplete user feedback. |

---

## 3. ROOT CAUSE CATEGORIES

| Type | Underlying Cause |
|------|------------------|
| **Protocol Interpretation** | Mis-mapping of F5 and ACK opcodes. |
| **Connection Stability** | Missing per-lens heartbeat / retry. |
| **Telemetry Integration** | No merge between case and lens snapshots. |
| **UI Feedback** | Absent callbacks for connect/disconnect and case updates. |

---

## 4. OBJECTIVES (Phase 4.6)

1. Achieve full **protocol parity** with Even Reality v1.6.6.  
2. Eliminate **false gesture** and **→ PING ← ERR** messages.  
3. Introduce **case telemetry** (battery, lid, charging).  
4. Restore **stable BLE link** > 5 min continuous.  
5. Enhance **UI feedback** on lens connection and case status.  

---

## 5. IMPLEMENTATION STRATEGY

### A. Event Reclassification
- Parse 0xF5 sub-opcodes:  
  - 0x00–0x05 → Gestures  
  - 0x06–0x0B → Wear / Case / Charging  
  - 0x0E–0x0F → Case Battery / Charging  
- Route non-gesture events to `SystemEvent` flow.  
- Silence gesture stream when lid closed or charging.

### B. Case Telemetry
- Query 0x2B (Get Silent Mode & State) and 0x2C (Get Battery Info).  
- Parse lid state + case battery % + charging flags.  
- Append fields to `DeviceTelemetrySnapshot`.

### C. Heartbeat & ACK Reliability
- Maintain per-lens 0x25 heartbeat timers.  
- Treat 0xCA as BUSY → retry once.  
- Handle 0xCB as CONTINUE → wait for C9 complete.  
- Remove ERR emission for valid BUSY/CONTINUE responses.

### D. Reconnect Handling
- Introduce per-lens retry loop (×3, 2 s back-off).  
- Skip re-bond if device already trusted.  

### E. UI / UX Enhancements
- Show case battery and lid state in Developer screen.  
- Add toast notifications for left/right connection success.  
- Replace “Gesture Unknown” spam with [CASE] / [WEAR] labels.  

---

## 6. PHASED TASKS AND DELIVERABLES

| Phase | Focus | Output |
|-------|--------|---------|
| Task 1 | F5 event classifier + system event flow | Correct gesture vs system separation (logs clean). |
| Task 2 | Case telemetry (0x2B/0x2C parse) | Case battery and lid open visible in UI. |
| Task 3 | Heartbeat + ACK realignment | No “→ PING ← ERR”; stable link > 60 s. |
| Task 4 | Reconnect stability | Lens reconnect reliable ≤ 2 attempts. |
| Task 5 | Telemetry persistence merge | Unified case + lens snapshot. |
| Task 6 | UX feedback and toast integration | Per-lens connection notices + updated HUD. |

---

## 7. VALIDATION CHECKLIST

✅ 0xF5 events filtered (no idle gesture spam).  
✅ Case battery % / lid open / charging accurate.  
✅ Heartbeat (0x25) steady at 30 s intervals.  
✅ No false “→ PING ← ERR” in console.  
✅ Reconnects succeed within 2 attempts.  
✅ Telemetry stable > 5 min runtime.  
✅ UI reflects case and lens states accurately.

---

## 8. EXIT CRITERIA
- Moncchichi Hub achieves complete parity with Even Reality v1.6.6 BLE workflow.  
- All telemetry flows (gesture, system, case, ACK) validated in 5-minute runtime tests.  
- Developer console and UI show synchronized lens + case data without error spam.  

---

**Commit Tag:**  
`phase4_r1i_ble_protocol_realignment_v166`
