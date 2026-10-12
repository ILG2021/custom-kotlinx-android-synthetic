<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# custom-kotlinx-android-synthetic Changelog

## [Unreleased]
### Fixed
- Preserve Java bound/unbound onBackPressed method references, resolve Kotlin return-label targets before renaming, and avoid extracted handler name collisions with superclass members (0.1.28).
- Fix system-back migration for exhaustive Java BACK branches, mixed Activity/window handlers and listener replacement during callbacks; preserve legacy key and direct onBackPressed behavior, and upgrade recognized 0.1.25 generated bridges on rescan (0.1.26).
### Added
- Extract ordinary Activity onBackPressed business logic into a private handler and remove the deprecated override; route dispatcher callbacks and same-class direct callers to the handler, and upgrade recognized existing bridges on rescan. Retain compatibility overrides for Activity inheritance and legacy onKeyDown entry points (0.1.27).
- Extend system-back migration to initialized onCreate methods, Activity inheritance, isolated onKeyDown BACK branches, Dialog key listeners, View PreIme overrides and recycled Kotlin View key listeners; exclude non-intercepting BACK comparisons and Fragment Boolean hooks (0.1.25).
- Migrate synchronous conditional super.onBackPressed calls with guards that preserve direct callers; automatically save manual-review reports with paths, line numbers and source snippets before preview (0.1.24).
- Add Tools → Synthetic Compatibility → Migrate Legacy System Back (Whole Project), with Java/Kotlin scanning, per-file diff preview, manual-review reasons, stale-file checks and global undo (0.1.22).
- Avoid redundant blocks when converting switches inside existing Java code blocks; clean older generated wrappers with preview and collision-safe cache names (0.1.20).
- Allow branch-local Java variable declarations in R.id switch migration after checking reference scopes; verify SeekBar callback behavior before and after conversion (0.1.19).
- Add Tools → Convert R.id Switches to If/Else (Whole Project), with Java PSI resolution, diff preview, conservative control-flow skips and global undo (0.1.18).
- Generate LayoutContainer extensions when its interface is present in module dependencies or Java sources; resolve the current containerView and preserve nullable qualifier-only lookups (0.1.14).
- Infer generic view parameters from the current variant's static Java source declarations, without compiling app classes; export source roots in IDE model format 3 and regenerate on Java source edits (0.1.12).
- Automatically derive generic view star projections from variant dependency class signatures; export the classpath for IDE regeneration, retaining explicit type overrides (0.1.10).
- Parcelize migration also previews and updates owning module Gradle scripts, enabling kotlin-parcelize or replacing direct Android Extensions declarations, with shared undo and conservative module skips (0.1.3).
- Project/directory Parcelize migration with Kotlin PSI edits, alias and wildcard imports, qualified references, diff preview and global undo. Includes test sources and provides module Gradle setup guidance.
- Add Kotlin ButterKnife migration and shared Java/Kotlin dependency planning, including cross-language bases, nullable/lateinit views, resources, listeners, aliased imports and Runnable cleanup. File and directory actions automatically preview related sources. IDE plugin version 0.1.1.
- Extend ButterKnife migration to resource annotations, all common listener callbacks, nullable and optional views, R2 cleanup, inner holders, multiple binds, Runnable unbinding and coordinated inheritance chains.
- PSI-based Java ButterKnife migration with directory scanning, per-file diff preview, conservative skips and global undo. Click listeners use standard Android OnClickListener without a ButterKnife runtime dependency.
- Shared layout generator and variant-aware Gradle plugin with automatic source registration.
- IDE refresh using Gradle-exported resource models, background generation and explicit VFS refresh.
- Resource overlays, qualified layouts, same-module includes, type diagnostics and stale-output cleanup.
- Generator compilation/runtime regression tests, real Android Gradle integration tests and a sample app.

### Changed
- Remove the redundant Tools → Regenerate Synthetic Views action; retain Gradle build generation and automatic IDE refresh (0.1.17).
- Remove kotlin.jvm.JvmField alongside migrated Kotlin ButterKnife property annotations, including aliases and fully qualified annotations; remove its import when unused (0.1.6).
- Removed global/tag view caches; properties always resolve against the current view tree.
- Replaced build-script namespace parsing with the Android variant model.
- Replaced the template tool window with a manual regeneration action and actionable diagnostics.

### Fixed
- Guard generated system-back callbacks against indirect dispatcher reentry, upgrade unchanged legacy bridges, and identify excluded/generated sources from IDE metadata instead of absolute path names (0.1.23).
- Keep included layout descendants in their own synthetic package, matching legacy synthetics and preventing ambiguous properties when parent and child packages are both imported (0.1.16).
- Remove standalone RecyclerView.ViewHolder extensions to match legacy synthetics and prevent ambiguity for holders implementing LayoutContainer; retain the old viewHolders option as a no-op (0.1.15).
- Isolate Java declaration parse errors by referenced class, so unrelated malformed sources do not block synthetic generation; preserve prior output when a referenced declaration is invalid (0.1.13).
- Track dependency classpath addresses as task inputs so relocation refreshes IDE models, and use model format 2 to reject incompatible generators (0.1.11).
- Apply the same nullable runtime lookup to every qualifier-only view type, including layout helpers and included descendants, without Barrier-specific filtering (0.1.9).
- Generate nullable properties for views absent in some layout alternatives, including included layouts; resolve optional IDs at runtime so filtered resource symbols do not break generated Kotlin compilation (0.1.7).
- Recognize explicitly imported Kotlin ButterKnife bind calls when IDE method resolution is unavailable, while rejecting shadowed symbols; verify nullable @JvmField views bound from Activity.onCreate through compilation and runtime cleanup tests (0.1.5).
- Harden Parcelize migration against indirect plugin aliases, Groovy slashy literals and ordinary plugin-name strings; preserve comments inside qualified references and avoid duplicate modern imports (0.1.4).
- Declare Kotlin K1/K2 mode support so Android Studio in K2 mode loads the plugin and registers its menus (0.1.2).
- Prevent JVM getter collisions for IDs differing only in initial letter case.
- Apply include root-ID replacement before merging qualified layouts.
- Ignore include container IDs for merge roots while retaining real descendants.
