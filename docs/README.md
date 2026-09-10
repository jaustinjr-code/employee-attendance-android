# Employee Attendance — Developer Documentation

Documentation for maintaining the **Employee Attendance** Android app. Written for a developer
joining the project who needs to locate feature code quickly and understand what else a change will
touch.

> [!IMPORTANT]
> **Known drift as of this sync (2026-09-10).** These guides were written on `main` and have just
> been ported to `develop`, which has moved on. They are accurate about the location, proximity,
> attendance and permission layers — the bulk of the material — but the following is known to be
> stale here, and a docs-accuracy pass is outstanding:
>
> | Documented | Actually on `develop` |
> | --- | --- |
> | `startup/AppStartup` + `ForegroundGate` gate app-lifetime work (overview §2, §8, package map; change-impact map) | replaced by `EmployeeAttendanceApplication.startupComplete` and the `StartupGate` composable; there is no `startup/` package |
> | The app bar title is pushed per destination via `LaunchedEffect` (attendance guide, change-impact map) | derived from the back stack through `AppNavGraph` / `appBarTitleResFor`, with an up button on child destinations |
> | No developer tooling | a debug-only developer settings screen exists — see [features/developer-settings.md](features/developer-settings.md) |
> | Screenshot tests and their goldens (testing guide §3) | not present on `develop`; the goldens were recorded against a since-changed UI and would need re-recording before they could be ported |
>
> Correct a section as you touch the code it describes, rather than trusting it wholesale.

## Start here

| If you want to… | Read |
| --- | --- |
| Get the app building and running | [onboarding.md](onboarding.md) |
| Understand the layering and module map | [architecture/overview.md](architecture/overview.md) |
| See the type graph (UML class diagrams) | [architecture/class-diagrams.md](architecture/class-diagrams.md) |
| See how a runtime flow actually executes | [architecture/sequence-diagrams.md](architecture/sequence-diagrams.md) |
| Find the code for one feature | [features/](features/) |
| Know what breaks if you change file X | [maintenance/change-impact-map.md](maintenance/change-impact-map.md) |
| Add a new feature the way this repo does it | [maintenance/adding-a-feature.md](maintenance/adding-a-feature.md) |
| Know which tests to write, and how to validate a change | [maintenance/testing.md](maintenance/testing.md) |
| Understand the docs-sync automation | [maintenance/documentation-workflow.md](maintenance/documentation-workflow.md) |

## Feature guides

- [features/attendance.md](features/attendance.md) — the home screen, clock in/out, live clock.
- [features/location-permissions.md](features/location-permissions.md) — the runtime permission
  ladder and rationale dialogs.
- [features/location-tracking.md](features/location-tracking.md) — location fixes, the foreground
  service, and the adaptive power policy.
- [features/proximity-and-geofencing.md](features/proximity-and-geofencing.md) — geofences,
  proximity state, and the arrival/departure event seam.
- [features/developer-settings.md](features/developer-settings.md) — the debug-only developer
  screen: state simulation, the permission override, and the log export.
- [features/work-location-registration.md](features/work-location-registration.md) — the work
  location model and the (stubbed) registration layer.

## One-paragraph orientation

The app is a **single-module Android app** (`:app`) built with Jetpack Compose, Material 3,
Navigation-Compose, and Kotlin coroutines/Flow. There is no DI framework: the dependency graph is
hand-wired in [`AppContainer`](../EmployeeAttendance/app/src/main/java/com/jaustinjr/employeeattendance/di/AppContainer.kt)
and hung off the `Application`. The dominant feature is **location**, which lives under
`location/` and is split into five sub-packages (`permission`, `tracking`, `proximity`, `geofence`,
`registration`, plus `ui`). Those sub-packages never talk to each other directly — they are joined
by one app-scoped orchestrator, [`LocationFeatureCoordinator`](../EmployeeAttendance/app/src/main/java/com/jaustinjr/employeeattendance/location/LocationFeatureCoordinator.kt).
Understanding that coordinator and the shared repositories it wires is 80% of understanding this
codebase.

## Diagram conventions

All diagrams are [Mermaid](https://mermaid.js.org/) fenced blocks, which GitHub renders natively.
Class diagrams show *structure and ownership*; sequence diagrams show *runtime flows across threads
and processes*. When you change a relationship shown in a diagram, update the diagram in the same
PR — see [maintenance/documentation-workflow.md](maintenance/documentation-workflow.md).
