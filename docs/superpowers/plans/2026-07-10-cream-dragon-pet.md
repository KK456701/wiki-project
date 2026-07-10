# Cream Dragon Pet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate, validate, and install an original cream-yellow 3D toy dragon as a Codex v2 animated pet.

**Architecture:** The hatch-pet preparation script owns prompts, layout guides, and the visual-job dependency graph. Isolated image-generation workers create only the base and coherent row strips; the parent copies approved results, runs deterministic extraction and atlas tooling, records QA, and installs the validated v2 package.

**Tech Stack:** Codex built-in image generation, hatch-pet Python scripts, bundled Python/Pillow runtime, JSON manifests, PNG/WebP.

## Global Constraints

- The mascot is an original cream-yellow baby dragon and must not reproduce an existing character's specific silhouette, facial proportions, or signature details.
- Style is a smooth soft 3D toy with cream-yellow body, off-white belly, rounded pale-orange dorsal fins, short limbs, thick short tail, and large dark-brown eyes.
- Do not add text, logos, clothes, held props, scenery, shadows, glow, or detached effects.
- Final atlas is exactly `1536x2288`, uses `192x208` cells, and is packaged with `spriteVersionNumber: 2`.
- All visual generation uses `$imagegen`; local scripts may only perform deterministic layout, extraction, cleanup, validation, QA composition, and packaging.
- Four cardinals and all 16 look directions must pass the hatch-pet semantic, continuity, blind-review, and v2 validation gates.

---

### Task 1: Prepare the Pet Run

**Files:**
- Read: `docs/superpowers/specs/2026-07-10-cream-dragon-pet-design.md`
- Create: `tmp/hatch-pet/cream-dragon/pet_request.json`
- Create: `tmp/hatch-pet/cream-dragon/imagegen-jobs.json`
- Create: `tmp/hatch-pet/cream-dragon/prompts/`
- Create: `tmp/hatch-pet/cream-dragon/references/layout-guides/`

**Interfaces:**
- Consumes: approved visual design specification.
- Produces: a prepared run directory whose `imagegen-jobs.json` defines every visual job, dependency, prompt, input image, and decoded output path.

- [ ] **Step 1: Prepare the run**

```powershell
$PYTHON='C:\Users\lenovo\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$SKILL_DIR='C:\Users\lenovo\.codex\skills\hatch-pet'
$RUN_DIR='F:\A-wiki-project\tmp\hatch-pet\cream-dragon'
& $PYTHON "$SKILL_DIR\scripts\prepare_pet_run.py" --pet-name '奶油幼龙' --pet-id 'cream-dragon' --description '一只温和好奇、略显笨拙的原创奶油黄色幼龙。' --output-dir $RUN_DIR --pet-notes '原创软萌3D玩偶幼龙；奶油黄色圆润身体，乳白肚皮，浅橙色圆角背鳍，短小四肢，粗短尾巴，大而清晰的深棕色眼睛；不得复刻任何现有角色的具体轮廓或标志性细节。' --style-preset '3d-toy' --style-notes 'smooth toy surface, compact readable silhouette, no fur, no text, no props, no shadows, no detached effects' --force
```

Expected: exit code `0`; `pet_request.json`, `imagegen-jobs.json`, prompts, and layout guides exist.

- [ ] **Step 2: Validate preparation outputs**

```powershell
$request=Get-Content -Raw "$RUN_DIR\pet_request.json" | ConvertFrom-Json
$jobs=Get-Content -Raw "$RUN_DIR\imagegen-jobs.json" | ConvertFrom-Json
if (-not $request.pet_id) { throw 'missing pet_id' }
if (($jobs.jobs | Measure-Object).Count -lt 12) { throw 'visual job graph is incomplete' }
$jobs.jobs | Select-Object id,kind,status,depends_on,output_path
```

Expected: the job graph includes `base`, nine standard states, `look-cardinals`, `look-row-9`, and `look-row-10`; only dependency-free jobs are initially ready.

### Task 2: Generate and Validate the Canonical Base

**Files:**
- Read: `tmp/hatch-pet/cream-dragon/prompts/base-pet.md`
- Create: `tmp/hatch-pet/cream-dragon/decoded/base.png`
- Create: `tmp/hatch-pet/cream-dragon/references/canonical-base.png`
- Modify: `tmp/hatch-pet/cream-dragon/imagegen-jobs.json`

**Interfaces:**
- Consumes: the prepared `base` job and its prompt.
- Produces: one approved, centered, full-body canonical identity image on the run's flat chroma background.

- [ ] **Step 1: Dispatch one isolated base worker**

Give the worker the hatch-pet base-worker prompt, the absolute run path, the `base` prompt file, and every input image listed by that job. Require exactly `selected_source=...` and `qa_note=...` in the response.

Expected: the selected image contains one complete original cream dragon, consistent with all Global Constraints.

- [ ] **Step 2: Copy and register the canonical base**

```powershell
$SOURCE=$selectedSource
$manifest=Get-Content -Raw "$RUN_DIR\imagegen-jobs.json" | ConvertFrom-Json
$job=$manifest.jobs | Where-Object id -eq 'base'
$output=Join-Path $RUN_DIR $job.output_path
New-Item -ItemType Directory -Force (Split-Path $output) | Out-Null
New-Item -ItemType Directory -Force "$RUN_DIR\references" | Out-Null
Copy-Item -LiteralPath $SOURCE -Destination $output -Force
Copy-Item -LiteralPath $output -Destination "$RUN_DIR\references\canonical-base.png" -Force
$job.status='complete'; $job | Add-Member source_path $SOURCE -Force; $job | Add-Member completed_at ([DateTime]::UtcNow.ToString('o')) -Force
$manifest | ConvertTo-Json -Depth 20 | Set-Content -Encoding utf8 "$RUN_DIR\imagegen-jobs.json"
```

Expected: both copied images exist and the `base` job is `complete`.

### Task 3: Generate and Validate Standard Animation Rows

**Files:**
- Create: `tmp/hatch-pet/cream-dragon/decoded/{idle,running-right,running-left,waving,jumping,failed,waiting,running,review}.png`
- Create: `tmp/hatch-pet/cream-dragon/qa/rows/*/review.json`
- Create: `tmp/hatch-pet/cream-dragon/final/spritesheet.webp`
- Create: `tmp/hatch-pet/cream-dragon/qa/contact-sheet.png`
- Create: `tmp/hatch-pet/cream-dragon/qa/previews/*.gif`
- Create: `tmp/hatch-pet/cream-dragon/qa/look-mechanics.md`

**Interfaces:**
- Consumes: canonical base plus each job's layout guide and prompt.
- Produces: nine incrementally approved standard rows, an 8x9 intermediate atlas, motion previews, and a pet-specific direction-mechanics decision.

- [ ] **Step 1: Generate identity and gait checks**

Dispatch separate workers for `idle` and `running-right`, each with the exact hatch-pet row-worker prompt and all `input_images` from the manifest. Copy each selected source to its declared `output_path`.

Expected: idle has visible micro-motion; running-right unmistakably faces and travels screen-right with an alternating cadence and no detached effects.

- [ ] **Step 2: Incrementally inspect each accepted row**

```powershell
foreach ($STATE in @('idle','running-right','running-left','waving','jumping','failed','waiting','running','review')) {
  if (Test-Path "$RUN_DIR\decoded\$STATE.png") {
    & $PYTHON "$SKILL_DIR\scripts\extract_strip_frames.py" --decoded-dir "$RUN_DIR\decoded" --output-dir "$RUN_DIR\qa\rows\$STATE\frames" --states $STATE --method auto
    & $PYTHON "$SKILL_DIR\scripts\inspect_frames.py" --frames-root "$RUN_DIR\qa\rows\$STATE\frames" --json-out "$RUN_DIR\qa\rows\$STATE\review.json" --states $STATE --require-components
  }
}
```

Expected: both commands exit `0`, `review.json` contains no errors, and warnings have been visually reviewed.

- [ ] **Step 3: Complete the remaining rows**

If mirroring preserves the symmetric dragon identity, derive `running-left` using `derive_running_left_from_running_right.py --confirm-appropriate-mirror`; otherwise generate it normally. Dispatch separate workers for `waving`, `jumping`, `failed`, `waiting`, `running`, and `review`, keeping up to three ready jobs active. Copy, inspect, and mark each job complete only after it passes Step 2.

Expected: all nine standard jobs are `complete`; no row changes face, proportions, material, palette, or dorsal-fin construction.

- [ ] **Step 4: Build and inspect the intermediate atlas**

```powershell
& $PYTHON "$SKILL_DIR\scripts\extract_strip_frames.py" --decoded-dir "$RUN_DIR\decoded" --output-dir "$RUN_DIR\frames" --states all --method auto
& $PYTHON "$SKILL_DIR\scripts\inspect_frames.py" --frames-root "$RUN_DIR\frames" --json-out "$RUN_DIR\qa\review.json" --require-components
& $PYTHON "$SKILL_DIR\scripts\compose_atlas.py" --frames-root "$RUN_DIR\frames" --output "$RUN_DIR\final\spritesheet.png" --webp-output "$RUN_DIR\final\spritesheet.webp"
& $PYTHON "$SKILL_DIR\scripts\make_contact_sheet.py" "$RUN_DIR\final\spritesheet.webp" --output "$RUN_DIR\qa\contact-sheet.png"
& $PYTHON "$SKILL_DIR\scripts\render_animation_previews.py" --frames-root "$RUN_DIR\frames" --output-dir "$RUN_DIR\qa\previews"
```

Expected: every command exits `0`; `qa/review.json` has no errors; contact sheet and GIFs show correct state semantics without cropping, drift, size popping, reversed cadence, or inert idle motion.

- [ ] **Step 5: Record natural look mechanics**

Write `qa/look-mechanics.md`: eyes lead; head turns/yaws or pitches subtly; dorsal fins follow slightly; lower torso and feet stay registered; each cardinal documents visible eye/nose/head landmarks, occlusion, and the body side revealed.

Expected: the document defines `000 up`, `090 screen-right`, `180 down`, `270 screen-left`, plus an even 22.5-degree motion budget.

### Task 4: Generate and Validate the 16 Look Directions

**Files:**
- Create: `tmp/hatch-pet/cream-dragon/decoded/look-anchors-approved.png`
- Create: `tmp/hatch-pet/cream-dragon/decoded/look-row-9.png`
- Create: `tmp/hatch-pet/cream-dragon/decoded/look-row-10.png`
- Create: `tmp/hatch-pet/cream-dragon/final/spritesheet-extended.webp`
- Create: `tmp/hatch-pet/cream-dragon/qa/direction-semantics.json`
- Create: `tmp/hatch-pet/cream-dragon/qa/direction-blind-validation.json`
- Create: `tmp/hatch-pet/cream-dragon/qa/look-continuity.json`

**Interfaces:**
- Consumes: canonical base, approved standard atlas/contact sheet, look mechanics, cardinals, and coherent row 9.
- Produces: a cleaned and deterministically validated 8x11 atlas with a continuous clockwise 16-direction loop.

- [ ] **Step 1: Generate and approve the four-cardinal strip**

Dispatch the isolated cardinal worker with all manifest inputs. Copy the result, then run:

```powershell
$CHROMA_KEY=(Get-Content -Raw "$RUN_DIR\pet_request.json" | ConvertFrom-Json).chroma_key.hex
& $PYTHON "$SKILL_DIR\scripts\extract_cardinal_anchors.py" --strip "$RUN_DIR\decoded\look-cardinals.png" --output-dir "$RUN_DIR\decoded\look-anchors" --chroma-key $CHROMA_KEY --json-out "$RUN_DIR\qa\cardinal-anchors.json"
& $PYTHON "$SKILL_DIR\scripts\compose_cardinal_anchor_strip.py" --anchors-dir "$RUN_DIR\decoded\look-anchors" --output "$RUN_DIR\decoded\look-anchors-approved.png"
```

Expected: all four anchors are complete and unclipped; visible landmarks unambiguously confirm up, screen-right, down, and screen-left.

- [ ] **Step 2: Generate, register, and approve coherent row 9**

Dispatch one row worker for `look-row-9` with every listed input. Copy the result and run:

```powershell
& $PYTHON "$SKILL_DIR\scripts\assemble_extended_atlas.py" --base-atlas "$RUN_DIR\final\spritesheet.webp" --look-row-9 "$RUN_DIR\decoded\look-row-9.png" --neutral-cell "$RUN_DIR\frames\idle\00.png" --chroma-key $CHROMA_KEY --chroma-threshold 96 --registered-row-output "$RUN_DIR\qa\look-row-9-registered.png" --registration-manifest-output "$RUN_DIR\qa\look-row-9-registration.json"
```

Expected: exit `0`; all eight registered cells pass final-edge and labeled semantic review in `000` through `157.5` order before the job is marked complete.

- [ ] **Step 3: Generate row 10 and assemble v2**

Dispatch one worker for `look-row-10`, including approved cardinals and completed row 9. Copy the result and run:

```powershell
& $PYTHON "$SKILL_DIR\scripts\assemble_extended_atlas.py" --base-atlas "$RUN_DIR\final\spritesheet.webp" --registered-row-9 "$RUN_DIR\qa\look-row-9-registered.png" --row-9-registration "$RUN_DIR\qa\look-row-9-registration.json" --look-row-10 "$RUN_DIR\decoded\look-row-10.png" --neutral-cell "$RUN_DIR\frames\idle\00.png" --chroma-key $CHROMA_KEY --chroma-threshold 96 --output "$RUN_DIR\final\spritesheet-extended.png" --webp-output "$RUN_DIR\final\spritesheet-extended.webp" --manifest-output "$RUN_DIR\final\spritesheet-extended.json"
& $PYTHON "$SKILL_DIR\scripts\despill_chroma_edges.py" "$RUN_DIR\final\spritesheet-extended.png" --output "$RUN_DIR\final\spritesheet-extended.png" --webp-output "$RUN_DIR\final\spritesheet-extended.webp" --chroma-key $CHROMA_KEY --json-out "$RUN_DIR\qa\chroma-despill-extended.json"
& $PYTHON "$SKILL_DIR\scripts\validate_atlas.py" "$RUN_DIR\final\spritesheet-extended.webp" --json-out "$RUN_DIR\final\validation-extended.json" --chroma-key $CHROMA_KEY --require-v2
```

Expected: all commands exit `0`; despill JSON has `ok: true`; v2 validation passes and reports `1536x2288`.

- [ ] **Step 4: Create visual and blind QA artifacts**

```powershell
& $PYTHON "$SKILL_DIR\scripts\make_contact_sheet.py" "$RUN_DIR\final\spritesheet-extended.webp" --output "$RUN_DIR\qa\contact-sheet-extended.png"
& $PYTHON "$SKILL_DIR\scripts\make_direction_qa_sheet.py" "$RUN_DIR\final\spritesheet-extended.webp" --output "$RUN_DIR\qa\look-directions.png"
& $PYTHON "$SKILL_DIR\scripts\make_direction_blind_qa_sheet.py" "$RUN_DIR\final\spritesheet-extended.webp" --output "$RUN_DIR\qa\direction-blind-pairs.png" --answer-key "$RUN_DIR\qa\direction-blind-answer-key.json"
& $PYTHON "$SKILL_DIR\scripts\measure_direction_continuity.py" "$RUN_DIR\final\spritesheet-extended.webp" --json-out "$RUN_DIR\qa\look-continuity.json"
```

Expected: all four QA artifacts exist; continuity review shows no visible snap, scale pop, registration jump, identity change, or reversal.

- [ ] **Step 5: Run three isolated blind reviews and final semantic QA**

Dispatch three fresh workers with `fork_turns="none"`; each may inspect only `direction-blind-pairs.png`. Save their exact JSON objects as verdict files 1–3, combine and validate them with the hatch-pet scripts, then dispatch one independent final visual-QA worker to create the 16-entry semantic verdict used for `qa/direction-semantics.json`.

Expected: `direction-blind-validation.json` has `ok: true`; all cardinals pass; `direction-semantics.json` contains 16 entries and no `fail`; any accepted intermediate warning has a written minor-resolution record.

### Task 5: Package, Install, and Verify

**Files:**
- Create: `C:/Users/lenovo/.codex/pets/cream-dragon/pet.json`
- Create: `C:/Users/lenovo/.codex/pets/cream-dragon/spritesheet.webp`
- Create: `tmp/hatch-pet/cream-dragon/qa/run-summary.json`

**Interfaces:**
- Consumes: the approved v2 spritesheet and all deterministic/visual QA evidence.
- Produces: an installed Codex custom pet and retained final QA artifacts.

- [ ] **Step 1: Install the approved pet**

```powershell
$request=Get-Content -Raw "$RUN_DIR\pet_request.json" | ConvertFrom-Json
$PET_DIR=Join-Path 'C:\Users\lenovo\.codex\pets' $request.pet_id
New-Item -ItemType Directory -Force $PET_DIR | Out-Null
Copy-Item "$RUN_DIR\final\spritesheet-extended.webp" "$PET_DIR\spritesheet.webp" -Force
[ordered]@{id=$request.pet_id;displayName=$request.display_name;description=$request.description;spriteVersionNumber=2;spritesheetPath='spritesheet.webp'} | ConvertTo-Json | Set-Content -Encoding utf8 "$PET_DIR\pet.json"
```

Expected: both files exist together and `pet.json.spriteVersionNumber` equals `2`.

- [ ] **Step 2: Revalidate the installed spritesheet**

```powershell
& $PYTHON "$SKILL_DIR\scripts\validate_atlas.py" "$PET_DIR\spritesheet.webp" --json-out "$RUN_DIR\final\validation-installed.json" --chroma-key $CHROMA_KEY --require-v2
```

Expected: exit `0` and installed atlas validation passes.

- [ ] **Step 3: Write the run summary and retain final evidence**

Write `qa/run-summary.json` with `ok: true`, `spriteVersionNumber: 2`, run directory, final spritesheet, validation, despill, contact sheet, direction sheet, semantic QA, blind validation, continuity, standard review, and installed package paths. Remove generated prompts, layout guides, decoded rows, extracted frames, PNG intermediates, 8x9 atlas, and job manifest only after the summary and installed validation pass.

Expected: the retained files match the hatch-pet acceptance list and the contact sheet is ready to show the user.
