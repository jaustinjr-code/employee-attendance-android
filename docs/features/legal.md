# Feature: Privacy policy and legal documents

Settings ends with a **Legal** section that has one row per legal document the app ships. Each row
shows the document's title and a one-line description, and tapping it opens the document read-only.
Today the only document is the **Privacy Policy**. Adding terms of use or open-source notices later
needs no new screen or route: each gets its own row in the same section.

```
Settings (Legal section) ─▶ LegalDocumentDetail(documentId)
```

---

## Code map

All paths are under `EmployeeAttendance/app/src/main/`.

| File | Role |
| --- | --- |
| `assets/legal/privacy_policy.md` | **The privacy policy text.** The single source: it is rendered in-app and can be published as-is wherever a store listing needs a public URL |
| `java/.../legal/LegalDocument.kt` | the registry enum: stable `id` (the route argument), title and summary strings (the Settings row's text), `assetPath` |
| `java/.../legal/LegalDocumentSource.kt` | `LegalDocumentSource` seam + `AssetLegalDocumentSource` (reads `assets/`) |
| `java/.../legal/LegalText.kt` | `LegalBlock` and `parseLegalText`, the Markdown-subset parser |
| `java/.../legal/ui/LegalDocumentViewModel.kt` | `LegalDocumentUiState` (`Loading` / `Loaded` / `Unavailable`), loads on `Dispatchers.IO`; `LEGAL_DOCUMENT_ID_ARG` |
| `java/.../legal/ui/LegalDocumentScreen.kt` | stateful `LegalDocumentScreen` + stateless `LegalDocumentContent` |
| `java/.../location/ui/SettingsScreen.kt` | the Legal section: one `NavigationRow` per `LegalDocument.entries`, tagged `SettingsTestTags.legalRow(document)`; `onOpenLegalDocument` |
| `java/.../ui/main/AppNavGraph.kt` | `LegalDocumentDetail` (parent `Settings`) |
| `java/.../MainActivity.kt` | the `LegalDocumentDetail(documentId)` route, navigated to from `SettingsScreen`'s `onOpenLegalDocument` |
| `java/.../di/AppContainer.kt` | `legalDocumentSource` |

The document screen uses the generic "Legal" app bar title. Titles are per destination, not per
route argument (see `appBarTitleResFor`), so the document's own level-1 heading names it.

---

## Adding a document

1. Write `assets/legal/<name>.md`. Start it with a `# Title` heading. Put an `Effective date:` line
   under the title if the document is versioned.
2. Add an entry to `LegalDocument` with a new, stable `id`, plus `legal_<name>_title` and
   `legal_<name>_summary` strings. These become the row's title and description in Settings, so
   keep the summary to one short sentence.

That is all. The Settings row, navigation, loading, and `LegalDocumentTest` (which checks that every entry's
asset exists and opens with a level-1 heading) pick it up automatically. **Never change an existing
`id`.** A restored back stack carries it, and an unknown id renders as "couldn't be loaded".

### Supported Markdown

`parseLegalText` handles only `#`/`##`/`###` headings, `- ` or `* ` bullets, and blank-line-separated
paragraphs. Hard-wrapped lines join. Anything else (emphasis, links, numbered lists) is shown as
**literal text**, on purpose: legal wording must reach the reader verbatim, so unsupported markup
should look wrong in review rather than silently lose characters.

---

## Keeping the privacy policy true

The policy makes specific claims about the code. When a change touches any of the following, update
`privacy_policy.md` and its effective date in the same PR:

| Policy claim | Backed by |
| --- | --- |
| No accounts, analytics, ads, or servers of our own; nothing sent to the developer | the merged manifest requests no `INTERNET` permission (geocoding and location go through system services); `StubWorkLocationRemoteDataSource` is a no-op. Adding `INTERNET` means re-reading the policy |
| Encrypted, on-device only, excluded from backup and D2D transfer | `SecurePreferences`; `allowBackup="false"` + `data_extraction_rules.xml` (`BackupRulesTest`) |
| No location history, only latest proximity state per worksite | `ProximityStateStore`; `LocationStateRepository` is in-memory |
| Address lookup goes to the device geocoder; reverse lookup can be turned off | `PlatformAddressGeocoder`; `PrivacySettingsStore.reverseGeocodeEnabled` |
| Reports leave only when the user shares them | `FileReportSharer` |
| What "Delete all data" removes (worksites, attendance, status updates, *not* name/settings) | `SettingsViewModel.onDeleteAllData` |
| The listed notification uses | `ClockNotifier`, `StatusUpdateNotifier`, `LocationTrackingService`, `BiweeklyReportWorker` |

Wiring a real backend into `WorkLocationRemoteDataSource`, adding a live `AddressAutocomplete`
provider (#6), a Maps SDK, crash reporting, or analytics all **invalidate** the policy as written.

---

## Tests

| Test | Layer | Covers |
| --- | --- | --- |
| `LegalTextTest` | JVM | the parser, including verbatim fallback for unsupported markup |
| `LegalDocumentTest` | JVM | unique ids, `fromId`, every registered asset exists and starts with `#`, the policy has an effective date. Reads the source tree, which is declared as a `Test` task input in `app/build.gradle.kts` |
| `LegalDocumentViewModelTest` | JVM | loaded / unknown id / missing id / `IOException` |
| `AppNavGraphTest` | JVM | `LegalDocumentDetail → Settings → Attendance` |
| `LegalScreensTest` | androidTest | assets are actually packaged, the real policy renders and scrolls, Settings shows a titled row for every document and each opens its own document |
