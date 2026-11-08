# MealPlanner AI: video script

Two videos: **Video 1 = GUI**, **Video 2 = CLI**. Do each step in order: first the **DO**, then read the **SAY**
line out loud. If something unexpected happens, describe what you see and keep going, or redo that step.

---

## Before you start (not recorded)

1. Open Terminal and run these two lines:
   `cd ~/Projects/mealplanner-ai`
   `rm -rf data/demo-gui data/demo-cli`
2. Turn on **Do Not Disturb**. Close other windows. Never open the `.env` file on screen.
3. Have this script open on your phone.

---

# VIDEO 1: GUI

**1. DO:** Press **Cmd+Shift+5** → **Options** → Microphone: **MacBook Microphone** → **Record**.

**2. DO:** In Terminal, type `java -jar build/libs/mealplanner.jar --data data/demo-gui` and press Enter.

**SAY:** "Hi, I'm Suleyman Kavas, and this is MealPlanner AI, my EECS3311 Stage 2 project. It helps a home cook
manage preferences, a pantry and recipes, and it uses a Gemini AI agent to recommend recipes and plan meals. This
video shows the GUI, and the second video shows the command line. Both use the same PlannerFacade."

**3. DO:** Point at the bottom-right corner of the window.

**SAY:** "Down here it shows the AI model I'm using, Gemini 3.5 Flash-Lite."

### F01: Preferences

**4. DO:** Click the **Preferences** tab.

**SAY:** "F01, manage dietary preferences."

**5. DO:** In **Household servings**, type `abc`. Click **Save preferences**.

**SAY:** "If I type something invalid, I get an error right under the form, and nothing is saved."

**6. DO:** Set Diet to **vegetarian**, servings `2`, max minutes `30`. In the list, click **peanut**. Click
**Save preferences**.

**SAY:** "Now I'm vegetarian, cooking for two, with a 30-minute limit, and I exclude peanuts. These preferences
are applied everywhere: search, recommendations and meal plans."

### F02: Pantry

**7. DO:** Click the **Pantry** tab.

**SAY:** "F02, manage my pantry."

**8. DO:** Choose **lentils**, type `500`, click **Set quantity**. Then **rice** `300`, Set quantity. Then
**onion** `2`, Set quantity.

**SAY:** "I'm adding what I have at home. Every ingredient has one fixed unit, like grams or a count."

**9. DO:** Choose **rice**, type `-5`, click **Set quantity**.

**SAY:** "Negative amounts are rejected."

**10. DO:** Choose **onion**, type `0`, Set quantity. Then click the onion row and click **Remove selected**.

**SAY:** "Zero means I'm out of it, and I can also remove an item completely."

### F03: Create and import recipes

**11. DO:** Click the **Recipes** tab → **Import JSON…** → open `samples` → `invalid-recipe.json` → **Open**.

**SAY:** "F03, create and import recipes. This file has an unknown ingredient and a negative amount, so it's
rejected and nothing is imported."

**DO:** Click **OK**.

**12. DO:** **Import JSON…** → `malformed-recipe.json` → **Open**.

**SAY:** "This file is broken JSON, so it's rejected too."

**DO:** Click **OK**.

**13. DO:** **Import JSON…** → `lentil-soup.json` → **Open**.

**SAY:** "This file is valid. I get a preview first. Nothing is saved until I accept it."

**DO:** Click **Accept and save**.

**SAY:** "It's now saved as recipe R22."

**14. DO:** Click **New recipe…**. Title `Garlic Rice`, servings `2`, minutes `15`. Click **Preview**, then
**Accept and save**.

**SAY:** "I can also type in a recipe. It's checked the same way, and saved as R23."

### F05: Recommend (AI)

**15. DO:** Click the **Recommend** tab. Keep the text "Use my lentils and keep meals under 30 minutes". Click
**Recommend recipes (AI)**.

**SAY (while it loads):** "F05, recommend recipes. This uses the AI agent. The agent can't just make things up.
It has to call tools inside my app to read my pantry and search my saved recipes."

**16. DO:** When the answer appears, scroll through it slowly.

**SAY:** "It recommends my lentil soup and explains why. The missing ingredients are calculated by Java, not by
the AI. At the bottom are the agent's steps: it looked up the ingredient and searched my recipes. Recommendations
never change my saved data."

**17. DO:** Replace the text with `How many calories are in each recipe?` and click **Recommend recipes (AI)**.

**SAY (after it answers):** "My app doesn't store calories, so the AI can't make up numbers. It can only answer
from my real recipe data."

### F04: Search

**18. DO:** Click the **Recipes** tab → **Show all**.

**SAY:** "F04, search and filter. Recipes that don't fit my preferences are marked ineligible, like the meat
dishes and anything over 30 minutes."

**19. DO:** Title contains: `soup`. Max minutes: `30`. Click **Search**.

**SAY:** "Searching for soups under 30 minutes finds only my lentil spinach soup."

**20. DO:** Clear both boxes. Ingredient IDs: `lentils`. Click **Search**. Then tick **Pantry only** and click
**Search** again.

**SAY:** "Pantry only checks that I have enough of every ingredient. I don't, so it honestly shows no results."

### F09: Scale servings

**21. DO:** Click **Show all** → click **R03 Overnight Oats** → **Scale servings…** → type `13` → **OK**.

**SAY:** "F09, adjust servings. Thirteen is out of range, so it's rejected."

**DO:** Click **OK**.

**22. DO:** **Scale servings…** → type `4` → **OK**.

**SAY:** "Going from one to four servings multiplies every amount exactly. For example, 50 grams of oats becomes
200."

**DO:** Click **Accept and save**.

**SAY:** "It's saved as a new recipe, and the original R03 is unchanged, because the app works on a copy. That's
the Prototype pattern."

### F08: Substitute an ingredient (AI)

**23. DO:** Click **R13 Fluffy Pancakes** → **Substitute ingredient (AI)…** → choose **milk** → request
`Use a dairy-free milk` → **OK**.

**SAY (while it loads):** "F08, ingredient substitution with AI. I want to replace the milk in these pancakes."

**24. DO:** Point at the new milk, the amount in ml, step 1, and the agent steps.

**SAY:** "The AI picked a plant milk from my ingredient catalogue, kept the amount in millilitres, and rewrote
the step. Java checked that this ingredient exists and that the recipe still fits my diet."

**DO:** Click **Accept and save**.

**25. DO:** Click **R13** in the list again.

**SAY:** "The original recipe still uses milk. The new version is saved separately."

### F06: Meal plan (AI)

**26. DO:** Click the **Meal plans** tab. Days `7`. Tick **Breakfast**, **Lunch** and **Dinner**. Tick
**No repeats**. Click **Generate plan (AI)**.

**SAY:** "F06, generate a meal plan. Twenty-one different meals isn't possible with my recipes, so Java stops it
before even calling the AI."

**DO:** Click **OK**.

**27. DO:** Untick Breakfast and Lunch. Days `5`. Keep **No repeats**. Wishes: `use my lentils early in the week`.
Click **Generate plan (AI)**.

**SAY (while it loads):** "Now five dinners. The dates, meal slots and servings come from my form. The AI only
chooses the recipes."

**28. DO:** Point at the plan, then at the agent steps.

**SAY:** "Here are five different dinners. In the agent steps you can see it searched my recipes and used the
checkPlan tool to validate the plan before answering. The plan is built by MealPlanBuilder, which is the Builder
pattern."

**DO:** Click **Accept and save**.

**SAY:** "It's saved as plan P01."

### F10: Shopping list

**29. DO:** Click **P01** → **Shopping list**.

**SAY:** "F10, the shopping list. It adds up every ingredient for the whole plan, one line per ingredient. It
says total ingredients required, because it doesn't subtract my pantry."

### F07: Revise a plan (AI)

**30. DO:** Click **P01** → **Revise with AI…** → type `Replace Tuesday dinner with a quicker dish` → **OK**.

**SAY (while it loads):** "F07, revise a plan in plain English."

**31. DO:** Point at **Changed meals**.

**SAY:** "Only Tuesday's dinner changed. The agent read the plan, searched for a quicker recipe and checked the
result."

**DO:** Click **Accept and save**.

**32. DO:** Click **P01**, then **P02**.

**SAY:** "The revision is saved as a new plan, P02. The original P01 is never overwritten."

### End

**33. DO:** Close the window. In Terminal, run the same command again:
`java -jar build/libs/mealplanner.jar --data data/demo-gui`. Click **Meal plans**.

**SAY:** "When I restart, everything is still here, because it's saved to a local JSON file. That's the GUI
demo."

**34. DO:** Stop recording (stop button in the top menu bar).

---

# VIDEO 2: CLI

**1. DO:** Press **Cmd+Shift+5** → **Options** → Microphone: **MacBook Microphone** → **Record**.

**2. DO:** Type `java -jar build/libs/mealplanner.jar llm-check`

**SAY:** "This is the command-line version. First I check the AI connection. It makes three real calls to
Gemini, and all three pass. My API key is in a local file that Git ignores, so it's never uploaded."

**3. DO:** Type `java -jar build/libs/mealplanner.jar cli --data data/demo-cli`, then type `help`

**SAY:** "This is the interactive CLI. It uses the same facade as the GUI."

### F01 and F02

**4. DO:** `profile set --diet vegan --servings 2 --max-minutes 30 --exclude peanut`

**SAY:** "F01. I set a vegan profile with a 30-minute limit."

**5. DO:** `profile set --diet keto`

**SAY:** "Keto isn't supported, so it's rejected."

**6. DO:** `pantry set --ingredient lentils --quantity 500`

**SAY:** "F02. I add lentils to my pantry."

**7. DO:** `pantry set --ingredient rice --quantity -1`

**SAY:** "Negative amounts are rejected."

### F03 and F04

**8. DO:** `recipe import --file samples/invalid-recipe.json`

**SAY:** "F03. An invalid recipe file is rejected."

**9. DO:** `recipe import --file samples/lentil-soup.json`, then `accept`

**SAY:** "A valid file shows a preview, and I accept it."

**10. DO:** `recipe search --text soup --max-minutes 30`

**SAY:** "F04. Searching finds the soup that fits my vegan preferences."

### F05 (AI)

**11. DO:** `agent recommend --request "Something warm with lentils, under 30 minutes"`

**SAY (while it loads):** "F05. The same AI agent, from the command line. Under the answer, it lists the tools
it used."

### F09 and an out-of-date preview

**12. DO:** `recipe scale --id R01 --servings 5`

**SAY:** "F09. I scale a recipe to five servings and get a preview."

**13. DO:** `pantry set --ingredient rice --quantity 200`, then `accept`

**SAY:** "But I changed my pantry before accepting, so the preview is out of date and the app refuses to save
it. That protects newer data."

**14. DO:** `recipe scale --id R01 --servings 5`, then `accept`

**SAY:** "If I make it again and accept right away, it's saved."

### F06 (AI)

**15. DO:** `agent plan --start 2026-11-16 --days 2 --slots dinner --servings 2 --no-repeat --request "simple meals"`
then `accept`, then `plan show --plan P01`

**SAY:** "F06. A two-day dinner plan made by the agent, saved as P01."

### F10

**16. DO:** `shopping generate --plan P01`

**SAY:** "F10. The shopping list for that plan."

**17. DO:** `shopping generate --plan P99`

**SAY:** "A plan that doesn't exist gives a clear error."

### F07 (AI)

**18. DO:** `agent revise --plan P01 --request "Replace the day 2 dinner with tacos"`, then `accept`, then
`plan list`

**SAY:** "F07. I revise the plan in plain English. Only day 2 changes, and it's saved as a new plan, P02."

**19. DO:** `status`, then `quit`

**SAY:** "Status shows how many AI calls and tokens this session used."

### Missing API key

**20. DO:** `GEMINI_API_KEY=YOUR_LOCAL_API_KEY_HERE java -jar build/libs/mealplanner.jar cli --data data/demo-cli`
then `agent recommend --request "anything"`, then `recipe search --text soup`, then `quit`

**SAY:** "Here I start the app without a real key. The AI feature is refused with a clear message, but search
still works."

### F08 (AI): the app checks the AI's answer

**21. DO:** `java -jar build/libs/mealplanner.jar cli --data data/demo-cli`, then
`agent substitute --recipe R03 --ingredient milk --request "Use a plant-based milk"`

**SAY (after it answers):** "F08. I asked for a plant milk in overnight oats, but my profile is vegan and the
recipe still has yogurt and honey. So the change is refused and nothing is saved. Java checks the AI's answer
instead of trusting it. The GUI video showed a successful substitution."

**22. DO:** `quit`

**SAY:** "That's the CLI demo. Thanks for watching."

**23. DO:** Stop recording.

---

## After recording

1. Go to **youtube.com** → **Create** (camera icon) → **Upload video** → choose the file from your Desktop.
2. Title "MealPlanner AI – Stage 2 demo (GUI)" or "(CLI)". Choose **No, it's not made for kids**, set
   Visibility to **Unlisted**, and click **Save**.
3. Copy the link and open it in a **private window** to check it plays.
4. Watch each video and write down when you say each feature ID (for example "F05 at 4:10").
5. Send me both links and the times.
