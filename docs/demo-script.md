# MealPlanner AI: demonstration recording script (Stage 2)

Two recordings cover every feature and show both interfaces:

| Video | Interface | Features | Target length |
|---|---|---|---|
| **Video 1** | GUI | F01–F10, with error cases | 14–18 min |
| **Video 2** | CLI | F01–F10 (shorter), live provider check, missing-key and stale-preview errors | 8–10 min |

Write down the start time (mm:ss) of every segment while recording. The report needs a link with a timestamp for
each feature (for YouTube: `https://youtu.be/VIDEO_ID?t=95` starts at 1:35).

---

## Before recording (checklist)

1. **Protect the key.** Never open `.env` on screen. Close any terminal window where the key was typed. Don't
   show AI Studio. The app itself never prints the key.
2. **Use a clean demo data folder** so recipe numbers match this script (R01–R21 are the starter recipes):
   ```bash
   cd ~/Projects/mealplanner-ai
   ./gradlew build
   rm -rf data/demo-gui data/demo-cli
   ```
3. Run `java -jar build/libs/mealplanner.jar llm-check` once off camera. It should print `RESULT: PASSED`.
4. Make the terminal font large (Cmd +) and maximise the GUI window. Turn off notifications (macOS Focus mode).
5. Record with QuickTime (File → New Screen Recording) or Cmd+Shift+5. Upload to **YouTube as Unlisted** or Google
   Drive with **Anyone with the link → Viewer**. Then open each link in a private window to confirm it plays
   without signing in.
6. AI steps take about 5–30 seconds. Keep the wait in the video (it shows the real call happening) or trim it.

For each segment, **say the feature ID and name first**, then what it does and whether AI is involved.

---

## Video 1: GUI

Start: `java -jar build/libs/mealplanner.jar --data data/demo-gui`

### 0. Intro (≈30 s)
Say: "This is MealPlanner AI, my EECS3311 project. The GUI and the CLI both call one PlannerFacade. The AI
features use Gemini 3.5 Flash-Lite through a bounded agent that calls local tools. Java validates everything,
and nothing the AI proposes is saved until I accept it." Point at the status bar showing the AI model.

### F01: Manage dietary preferences (deterministic)
1. Preferences tab. Set **Household servings** to `abc` and click **Save preferences**.
   Expected: red field error *"servings: 'abc' is not a whole number."*, and nothing is saved.
2. Set Diet = `vegetarian`, servings = `2`, max minutes = `30`. In the list, select `peanut` (and `mushroom` if you
   like). Click **Save**. Expected: status *"Preferences saved."*
3. Say: "These preferences now constrain search, recommendations and plans."

### F02: Manage pantry inventory (deterministic)
1. Pantry tab. Choose `lentils`, quantity `500`, click **Set quantity**. Add `rice` = `300` and `onion` = `2`.
2. Error: set `rice` to `-5` and click **Set**. Expected: *"quantity: must not be negative."*
3. Set `onion` to `0`. Say: "Zero is kept but treated as unavailable." Select the onion row and click
   **Remove selected**.

### F03: Create and import structured recipes (deterministic)
1. Recipes tab → **Import JSON…** → `samples/invalid-recipe.json`. Expected: errors for the unknown ingredient
   `dragon-fruit-powder` and the negative rice quantity. Nothing is imported.
2. **Import JSON…** → `samples/malformed-recipe.json`. Expected: *"Malformed recipe JSON…"*
3. **Import JSON…** → `samples/lentil-soup.json`. A preview window opens. Say "it is not saved yet." Click
   **Accept and save**. Expected: *"Saved recipe R22 'Lentil Spinach Soup'."*
4. **New recipe…**: title `Garlic Rice`, servings `2`, minutes `15`, ingredients `rice=200` and `garlic=2`, two
   steps → **Preview** → **Accept and save** (R23).

### F04: Search and filter recipes (deterministic)
1. Click **Show all**. Point out the `[ineligible]` labels (meat dishes, and anything over 30 minutes).
2. Type `soup` in Title and `30` in Max minutes, then **Search**. Expected: only eligible soups ≤ 30 min (R22).
3. Clear the title and max minutes, type `lentils` in Ingredient IDs, then **Search** (lentil recipes). Now tick
   **Pantry only** and search again. Expected: no results, because the pantry has lentils but not every other
   ingredient. Say: "pantry-only requires enough stock of every ingredient at base servings; it compares
   quantities, not just names."
4. Empty result: title `zzz` → **Search**. Expected: the "No eligible recipe matches…" message. Say: "constraints
   are never silently relaxed."

### F09: Adjust recipe serving quantities (deterministic)
1. Select **R03 Overnight Oats** (1 serving) → **Scale servings…** → `13`. Expected: error, servings must be 1–12.
2. **Scale servings…** → `4`. The preview shows 200 g oats, 480 ml milk and the note about cooking time.
   **Accept and save**. Say: "the original R03 is unchanged; the variant is a deep copy (Prototype)."

### F08: Suggest and apply ingredient substitutions (hybrid, AI)
1. Select **R13 Fluffy Pancakes** → **Substitute ingredient (AI)…** → ingredient `milk`, request
   `Use a dairy-free milk` → OK.
2. In the preview, point to: the replacement (for example oat or soy milk), the quantity in **ml**, the rewritten
   step, and the **AI agent steps** (`readProfile`, `listIngredients`, …). Say: "the AI proposes; Java checks that
   the ingredient was retrieved through a tool, exists in the catalogue and fits my diet."
3. **Accept and save**, then show that R13 still contains milk.
4. Optional error: request `replace it with unicorn milk`. The agent should answer NO_MATCH, or Java rejects the
   answer. Either way, nothing is saved.

### F05: Recommend recipes (hybrid, AI)
1. Recommend tab. Request: `Use my lentils and keep meals under 30 minutes` → **Recommend recipes (AI)**.
2. Show the ranked list: reasons, *"Missing from pantry (at base servings)"* and **AI agent steps** (readPantry,
   searchRecipes…). Say: "the model ranks and explains; Java computes the missing quantities, and recommendations
   never save anything."
3. Unsupported request: `How many calories are in each recipe?`. Expected: a "not supported" or clarification
   message instead of made-up numbers.

### F06: Generate a meal plan (hybrid, AI)
1. Meal plans tab. Start = next Monday, Days = `7`, tick **Breakfast**, **Lunch** and **Dinner**, tick
   **No repeats** → **Generate plan (AI)**. Expected *immediately*, with no AI call: *"A no-repeat plan needs 21
   different recipes, but only N stored recipes fit your preferences…"* Say: "impossible constraints are caught in
   Java before calling the model."
2. Untick Breakfast and Lunch (Dinner only), Days = `5`, keep No repeats, Wishes = `use my lentils early in the
   week` → **Generate plan (AI)**.
3. The preview shows 5 dinners with dates and weekdays, the explanation, and agent steps including **checkPlan →
   valid**. Say: "PlanService directs MealPlanBuilder (Builder); the dates, slots and servings came from my form,
   not from the model." **Accept and save**. Expected: *"Saved plan P01…"*

### F07: Revise a plan through natural language (hybrid, AI)
1. Select **P01** → **Revise with AI…** → `Replace Tuesday dinner with a pasta dish`.
2. The preview lists **Changed meals: Day 2 Dinner: X → Y**. Agent steps include `readPlan`, `searchRecipes` and
   `checkPlan`. **Accept and save**.
3. Show the list now has **P01** and **P02 (revised from P01)**. Select P01 and show Tuesday unchanged. Say:
   "revision works on a deep copy (Prototype); the original is never overwritten."
4. Error: revise P01 with `Change Sunday breakfast`. The plan has no breakfast and no Sunday. Expected: a
   clarification message or a validation error, with nothing saved.

### F10: Generate a consolidated shopping list (deterministic)
1. Select **P02** → **Shopping list**. Point out the title **"Total ingredients required … pantry stock is NOT
   deducted"**, one line per ingredient, and the canonical units. Say: "totals use BigDecimal and the plan's own
   recipe snapshots."

### Wrap-up (≈20 s)
Close the window and restart with the same `--data data/demo-gui` to show that preferences, recipes and plans
were saved.

---

## Video 2: CLI

Start in a terminal (large font). Avoid showing your shell history.

### 0. Provider check and intro (≈1 min)
```bash
java -jar build/libs/mealplanner.jar llm-check
```
Say: "Three real calls: one tool call, a conversation replay, and structured JSON output. The key comes from a
Git-ignored .env file." Then:
```bash
java -jar build/libs/mealplanner.jar cli --data data/demo-cli
help
```

### F01 / F02
```
profile set --diet vegan --servings 2 --max-minutes 30 --exclude peanut
profile set --diet keto
pantry set --ingredient lentils --quantity 500
pantry set --ingredient rice --quantity -1
pantry show
```
The second and fourth commands show validation errors.

### F03 / F04
```
recipe import --file samples/invalid-recipe.json
recipe import --file samples/lentil-soup.json
accept
recipe search --text soup --max-minutes 30
recipe search --ingredient lentils --pantry-only
```

### F09 and a stale preview
```
recipe scale --id R01 --servings 5
pantry set --ingredient rice --quantity 200
accept
```
Expected: *"This preview is out of date…"*. Say: "a proposal records the revision it was made from; any save in
between makes it stale." Then `recipe scale --id R01 --servings 5` and `accept`.

### F05 (AI)
```
agent recommend --request "Something warm with lentils, under 30 minutes"
```

### F06 (AI)
```
agent plan --start 2026-11-16 --days 3 --slots lunch,dinner --servings 2 --no-repeat --request "simple meals"
accept
plan show --plan P01
```

### F07 (AI)
```
agent revise --plan P01 --request "Replace the day 2 dinner with tacos"
accept
plan list
```

### F08 (AI): validation of an AI suggestion
```
recipe show --id R03
agent substitute --recipe R03 --ingredient milk --request "Use a plant-based milk"
```
With the vegan profile, Overnight Oats still contains yogurt and honey after any single swap. Expected: the agent
answers NO_MATCH, or Java rejects the variant ("contains Plain yogurt, which is not vegan"), and nothing is saved.
Say: "the AI suggestion is checked independently; an invalid variant is never offered for saving." The GUI video
shows a successful substitution.

### F10
```
shopping generate --plan P02
shopping generate --plan P99
status
quit
```
`status` shows the model and the session's model calls and tokens.

### Missing-key behaviour (≈30 s)
Start the CLI with the placeholder key. It overrides `.env` for this one run only and shows nothing secret:
```bash
GEMINI_API_KEY=YOUR_LOCAL_API_KEY_HERE java -jar build/libs/mealplanner.jar cli --data data/demo-cli
agent recommend --request "anything"
recipe search --text soup
quit
```
Expected: a clear "AI features are off…" message, while search still works.

---

## Timestamp table (fill in, then copy into the report)

| Feature | Video 1 (GUI) start | Video 2 (CLI) start |
|---|---|---|
| F01 Manage dietary preferences | | |
| F02 Manage pantry inventory | | |
| F03 Create and import structured recipes | | |
| F04 Search and filter recipes | | |
| F05 Recommend recipes | | |
| F06 Generate a meal plan | | |
| F07 Revise a plan through natural language | | |
| F08 Suggest and apply ingredient substitutions | | |
| F09 Adjust recipe serving quantities | | |
| F10 Generate a consolidated shopping list | | |
| Error / alternative behaviour | | |
