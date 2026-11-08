# MealPlanner AI: video recording cue sheet (Stage 2)

Two videos. **Video 1 = GUI (~15 min), Video 2 = CLI (~10 min).** Together they show every feature (F01–F10),
both interfaces, the AI agent and the error handling.

Each step below has three parts: **DO** (what to click or type), **SAY** (one or two sentences, in your own
words), and **EXPECT** (what should appear).

---

## Before recording (5 minutes)

1. In Terminal:
   ```
   cd ~/Projects/mealplanner-ai
   ./gradlew build
   rm -rf data/demo-gui data/demo-cli
   ```
2. **Never show your key.** Don't open `.env`, don't run `cat .env`, and don't show AI Studio.
3. Turn on Do Not Disturb (Control Centre → Focus). Close other windows. Make the Terminal font big (Cmd +, three
   or four times).
4. Keep this sheet on your phone or a second screen, not in the recorded area.
5. **How to record on a Mac:** press **Cmd+Shift+5**, then click **Options → Microphone → MacBook Microphone**
   (otherwise there is no sound). Choose **Record Entire Screen** (or **Record Selected Portion** around the app),
   then click **Record**. To stop, click the stop button in the top menu bar. The video is saved to your Desktop.
6. Start each segment by **saying the feature ID and name** ("F05, recommend recipes"). That makes the timestamps
   easy to find afterwards.
7. AI steps take 5–30 seconds. Just wait, or say what the agent is doing. If a "rate limit" message appears, keep
   it (it shows error handling), wait a minute and repeat the step.

---

## VIDEO 1: GUI

**DO:** in Terminal, run `java -jar build/libs/mealplanner.jar --data data/demo-gui`

### Intro (30 s)
**SAY:** "This is MealPlanner AI, my EECS3311 project. The GUI and the CLI both go through one PlannerFacade. The
AI features use Gemini 3.5 Flash-Lite through an agent that calls local tools. Java checks everything the AI
returns, and nothing is saved until I accept it." Point at the bottom-right status bar (AI model).

### F01: Manage dietary preferences (deterministic)
- **DO:** Preferences tab. Servings = `abc` → **Save preferences**.
  **EXPECT:** red error "servings: 'abc' is not a whole number". **SAY:** "Field-level validation; nothing saved."
- **DO:** Diet = **vegetarian**, servings = `2`, max minutes = `30`. In the list, click **peanut** → **Save**.
  **EXPECT:** "Preferences saved." **SAY:** "These preferences now filter search, recommendations and plans."

### F02: Manage pantry inventory (deterministic)
- **DO:** Pantry tab. Ingredient **lentils**, quantity `500` → **Set quantity**. Then **rice** `300`, **onion** `2`.
- **DO:** rice, quantity `-5` → **Set quantity**. **EXPECT:** "quantity: must not be negative."
- **DO:** onion, quantity `0` → **Set**. **SAY:** "Zero is kept but treated as unavailable." Select the onion row →
  **Remove selected**.

### F03: Create and import recipes (deterministic)
- **DO:** Recipes tab → **Import JSON…** → `samples/invalid-recipe.json`.
  **EXPECT:** errors for unknown ingredient `dragon-fruit-powder` and the negative quantity. **SAY:** "Nothing is
  partially imported."
- **DO:** **Import JSON…** → `samples/malformed-recipe.json`. **EXPECT:** "Malformed recipe JSON".
- **DO:** **Import JSON…** → `samples/lentil-soup.json`. A preview opens. **SAY:** "Preview first." Click
  **Accept and save**. **EXPECT:** "Saved recipe R22 'Lentil Spinach Soup'."
- **DO:** **New recipe…** → title `Garlic Rice`, servings `2`, minutes `15` (keep the sample ingredients and steps)
  → **Preview** → **Accept and save** (saved as R23).

### F05: Recommend recipes (AI)
- **DO:** Recommend tab. Keep the text "Use my lentils and keep meals under 30 minutes" → **Recommend recipes (AI)**.
- **EXPECT:** Lentil Spinach Soup with a reason, "Missing from pantry …" and **AI agent steps** (listIngredients,
  searchRecipes).
- **SAY:** "The agent searched my stored recipes with tools. Java checked that the recipe really came from those
  tools and fits my diet, and calculated what's missing. Recommending never saves anything."
- **DO (error):** replace the text with `How many calories are in each recipe?` → **Recommend**.
  **EXPECT:** a "not supported" or clarification message. **SAY:** "It explains instead of inventing numbers."

### F04: Search and filter recipes (deterministic)
- **DO:** Recipes tab → **Show all**. **SAY:** "Meat dishes and anything over 30 minutes are labelled
  ineligible."
- **DO:** Title `soup`, Max minutes `30` → **Search**. **EXPECT:** only R22 Lentil Spinach Soup.
- **DO:** clear the title and max minutes, Ingredient IDs `lentils` → **Search** (R22). Tick **Pantry only** →
  **Search**. **EXPECT:** no results. **SAY:** "Pantry-only compares quantities, and I don't have every ingredient,
  so it honestly shows nothing."

### F09: Adjust serving quantities (deterministic)
- **DO:** **Show all** → select **R03 Overnight Oats** → **Scale servings…** → `13`. **EXPECT:** error (1–12 only).
- **DO:** **Scale servings…** → `4`. **EXPECT:** 200 g oats, 480 ml milk, and a note about cooking time.
  **Accept and save** (saved as R24).
- **SAY:** "The original R03 is unchanged. Scaling works on a deep copy, which is the Prototype pattern, and uses
  exact BigDecimal maths."

### F08: Substitute an ingredient (AI)
- **DO:** select **R13 Fluffy Pancakes** → **Substitute ingredient (AI)…** → ingredient **milk**, request
  `Use a dairy-free milk` → OK.
- **EXPECT:** preview with soy or oat milk in **ml**, a rewritten step and **AI agent steps** (readProfile,
  listIngredients).
- **SAY:** "The AI proposes. Java checks that the ingredient came from the catalogue tool and that the new recipe
  still fits my diet." **Accept and save**, then select R13 again to show it still uses milk.

### F06: Generate a meal plan (AI)
- **DO:** Meal plans tab. Days `7`, tick **Breakfast**, **Lunch** and **Dinner**, tick **No repeats** →
  **Generate plan (AI)**.
  **EXPECT (instantly):** "A no-repeat plan needs 21 different recipes, but only 17 stored recipes fit…" (the number can differ by one)
  **SAY:** "Impossible requests are caught in Java before the AI is called."
- **DO:** untick Breakfast and Lunch, Days `5`, keep No repeats, Wishes `use my lentils early in the week` →
  **Generate plan (AI)**.
- **EXPECT:** 5 dinners with dates, a "Why" explanation, and agent steps including **checkPlan → valid**.
- **SAY:** "The dates, slots and servings come from my form. The AI only picks recipes. MealPlanBuilder only builds
  complete plans, which is the Builder pattern." **Accept and save** → "Saved plan P01".

### F10: Consolidated shopping list (deterministic)
- **DO:** select **P01** → **Shopping list**.
- **SAY:** "One line per ingredient, in its own unit. It's labelled Total ingredients required because pantry stock
  is not deducted."

### F07: Revise a plan (AI)
- **DO:** select **P01** → **Revise with AI…** → `Replace Tuesday dinner with a quicker dish` → OK.
- **EXPECT:** "Changed meals: Day 2 Dinner: X → Y" and agent steps (readPlan, searchRecipes, checkPlan).
  **Accept and save**.
- **SAY:** "The revision is saved as P02. P01 is never overwritten." Select P01 and point at Tuesday (unchanged),
  then select P02.

### End (20 s)
- **DO:** close the window, run the same start command again, and show that the plans and recipes are still there.
  **SAY:** "Everything is saved locally as JSON." Stop recording.

---

## VIDEO 2: CLI

Type or paste the commands one at a time. Keep the AI steps spread out as listed.

### Intro and live provider check
```
java -jar build/libs/mealplanner.jar llm-check
```
**SAY:** "This makes three real Gemini calls: a tool call, a conversation replay and structured JSON. The key is in a
Git-ignored .env file."

```
java -jar build/libs/mealplanner.jar cli --data data/demo-cli
help
```

### F01 and F02
```
profile set --diet vegan --servings 2 --max-minutes 30 --exclude peanut
profile set --diet keto
pantry set --ingredient lentils --quantity 500
pantry set --ingredient rice --quantity -1
pantry show
```
**SAY:** "Both invalid inputs are rejected with clear messages."

### F03 and F04
```
recipe import --file samples/invalid-recipe.json
recipe import --file samples/lentil-soup.json
accept
recipe search --text soup --max-minutes 30
```

### F05 (AI)
```
agent recommend --request "Something warm with lentils, under 30 minutes"
```
**SAY:** "Same agent as in the GUI; the steps are listed under the answer."

### F09 and a stale preview
```
recipe scale --id R01 --servings 5
pantry set --ingredient rice --quantity 200
accept
```
**EXPECT:** "This preview is out of date…". **SAY:** "Something was saved after the preview, so it's rejected
instead of overwriting newer data."
```
recipe scale --id R01 --servings 5
accept
```

### F06 (AI)
```
agent plan --start 2026-11-16 --days 2 --slots dinner --servings 2 --no-repeat --request "simple meals"
accept
plan show --plan P01
```

### F10
```
shopping generate --plan P01
shopping generate --plan P99
```
**EXPECT:** the totals, then "No plan 'P99'".

### F07 (AI)
```
agent revise --plan P01 --request "Replace the day 2 dinner with tacos"
accept
plan list
status
quit
```
**SAY:** `status` shows the model calls and tokens used in this session.

### Missing-key behaviour
This uses the placeholder text, not a real key:
```
GEMINI_API_KEY=YOUR_LOCAL_API_KEY_HERE java -jar build/libs/mealplanner.jar cli --data data/demo-cli
agent recommend --request "anything"
recipe search --text soup
quit
```
**SAY:** "Without a key the AI is refused with a clear message, but the other features still work."

### F08 (AI): Java rejects an unsafe AI suggestion
```
java -jar build/libs/mealplanner.jar cli --data data/demo-cli
recipe show --id R03
agent substitute --recipe R03 --ingredient milk --request "Use a plant-based milk"
quit
```
**EXPECT:** a NO_MATCH answer, or a rejection because the oats still contain yogurt and honey (not vegan). Nothing is
saved. **SAY:** "Even if the AI suggests something, Java checks the whole recipe against my vegan profile. The GUI
video shows a successful substitution." Stop recording.

---

## After recording

1. Go to **youtube.com → Create (camera icon) → Upload video**. Pick the file from your Desktop.
   - Title: "MealPlanner AI – Stage 2 demo (GUI)" or "(CLI)".
   - Audience: "No, it's not made for kids".
   - Visibility: **Unlisted** → Save.
2. Copy the link (for example `https://youtu.be/abc123XYZ`). Open it in a **private window** to check it plays
   without signing in.
3. Watch it and write down when each feature starts (mm:ss), using the table below.
4. Send the two links and the times. The report needs a timestamped link for each feature (for example,
   `?t=135` for 2:15).

| Feature | Video 1 (GUI) start | Video 2 (CLI) start |
|---|---|---|
| F01 Preferences | | |
| F02 Pantry | | |
| F03 Create/import recipes | | |
| F04 Search | | |
| F05 Recommend (AI) | | |
| F06 Meal plan (AI) | | |
| F07 Revise plan (AI) | | |
| F08 Substitution (AI) | | |
| F09 Scale servings | | |
| F10 Shopping list | | |
| Errors: provider check, stale preview, missing key | — | |
