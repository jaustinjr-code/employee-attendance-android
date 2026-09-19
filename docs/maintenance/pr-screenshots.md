# Screenshots in pull request descriptions

A pull request with a visual UI/UX change shows the change in its description, so a reviewer does not
have to check out the branch to see it.

| Kind of change | What the description needs |
| --- | --- |
| UI/UX bug fix | a **before** and an **after** image of the same screen and state |
| New feature | a preview image, or the screenshot-test images that cover it |
| Behaviour that only shows over time (a transition, a flow) | a screen recording, or a short image sequence |
| No visual change | nothing; do not add screenshots for the sake of it |

## Hosting: an orphan branch

`gh` cannot attach an image to a PR, and committing PNGs to the feature branch puts them in the code
diff. Instead, push them to an **orphan branch named `pr-assets/pr-<number>`** and link them from the
description. The images never appear in the PR's diff.

The PR number is needed for the branch name, so open the PR first, then add the images by editing the
description.

### 1. Capture

Build the "before" from the PR's base branch in a temporary worktree (copy
`EmployeeAttendance/local.properties` into it first) and the "after" from the feature branch.

```bash
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk   # no -q: this adb rejects it
adb shell am start -n com.jaustinjr.employeeattendance/.MainActivity
adb exec-out screencap -p > after.png
```

Uninstall between builds (`adb uninstall com.jaustinjr.employeeattendance`) so both start from the
same state. To tap a control, find its bounds with `adb shell uiautomator dump` rather than guessing
coordinates. Look at every image before publishing it: a launcher, a permission dialog or a
"before" that matches the "after" is a wasted capture.

Shrink them; a full-resolution phone screenshot makes a very tall description:

```bash
sips -Z 600 after.png --out after-s.png      # macOS
```

### 2. Publish to the assets branch

Do this from a throwaway worktree so the working tree and the feature branch are untouched:

```bash
git worktree add --detach "$SCRATCH/assets" HEAD
cd "$SCRATCH/assets"
git checkout --orphan pr-assets/pr-<n> && git rm -rf .
mkdir pr-<n> && cp before-s.png pr-<n>/before.png && cp after-s.png pr-<n>/after.png
git add pr-<n> && git commit -m "Screenshots for PR #<n>"
git push -u origin pr-assets/pr-<n>
cd - && git worktree remove --force "$SCRATCH/assets" && git branch -D pr-assets/pr-<n>
```

Pushing a branch is outward-facing; it is covered by the developer's request for screenshots, but
say in the PR hand-off that it was created.

### 3. Link from the description

Use the `blob/…?raw=true` form, which renders for anyone with access to the repository (a private
repo's `raw.githubusercontent.com` links do not). Pin the width so the image is not enormous:

```markdown
| Before | After |
| --- | --- |
| <img src="https://github.com/<owner>/<repo>/blob/pr-assets/pr-<n>/pr-<n>/before.png?raw=true" width="300"> | <img src="https://github.com/<owner>/<repo>/blob/pr-assets/pr-<n>/pr-<n>/after.png?raw=true" width="300"> |
```

Update the description with `gh pr edit <n> --body-file <file>`. Read the current body first
(`gh pr view <n> --json body -q .body`) and insert a section rather than replacing it.

### Cleaning up

The assets branch has no purpose once the PR is merged. Delete it then:
`git push origin --delete pr-assets/pr-<n>`. Deleting it earlier breaks the images in the description.

## Screenshot tests are not PR screenshots

The golden-image tests in [testing.md](testing.md) guard against regressions; they are not a substitute
for showing a reviewer the change. For a new feature covered by a screenshot test, its recorded
golden is an acceptable preview image.
