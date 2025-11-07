# CONTEXT SUMMARY
See CONTEXT_ENGINEERING.md v1.4 §4.

# TASK – Consolidate G1ReplyParser into telemetry package
Objective:
Choose core/.../telemetry/G1ReplyParser as canonical; redirect/remove the duplicate under core/.../core/ble.

Files:
• core/.../telemetry/G1ReplyParser.kt (keep)
• core/.../core/ble/G1ReplyParser.kt (delete or thin wrapper)

Changes:
1) Move any unique helpers from the core/ble variant into the telemetry variant.
2) Update imports across modules to reference telemetry/G1ReplyParser.
3) Delete the duplicate (or leave a 1-file shim that delegates, marked @Deprecated, then remove in a later patch).

Expected Behaviour:
- Single source of truth for parsing helpers.
- All callers compile; unit tests pass; ProtocolMap handlers use the canonical parser helpers.