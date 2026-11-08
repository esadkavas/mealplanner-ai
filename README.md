# MealPlanner AI

EECS3311 course project (Suleyman Kavas). A Java desktop and command-line assistant that helps a home cook
manage preferences, pantry and recipes, and uses an LLM agent to recommend recipes, generate and revise
meal plans, and suggest ingredient substitutions. Java code validates everything the AI proposes, and nothing
the AI suggests is saved until you accept it.

- **Stage 1 design:** `MealPlanner_Stage1_Design.docx`
- **Stage 2 implementation report:** `stage2-report.pdf`

## Prerequisites

| Requirement | Notes |
|---|---|
| JDK 21 or newer | `java -version`. The build compiles to Java 21 bytecode. |
| Git | to clone the repository |
| Internet access on first build | the Gradle wrapper downloads Gradle and the libraries (Jackson, JUnit) |
| Google AI Studio API key | optional; only needed for the AI features (F05–F08). Free tier available at https://aistudio.google.com |

No database, server or other installation is needed. The Gradle wrapper (`gradlew`) is included.

## 1. Install and build

```bash
git clone https://github.com/esadkavas/mealplanner-ai.git
cd mealplanner-ai
./gradlew build          # Windows: gradlew.bat build
```

`build` compiles the code, runs the JUnit tests and creates the runnable jar `build/libs/mealplanner.jar`.

## 2. Configure the AI key locally (never commit it)

The key is read from a local `.env` file that Git ignores (see `.gitignore`). Only `.env.example`, which holds a
placeholder, is committed.

```bash
cp .env.example .env     # Windows: copy .env.example .env
```

Open `.env` and replace `YOUR_LOCAL_API_KEY_HERE` with your key:

```
GEMINI_API_KEY=your-key-here
GEMINI_MODEL=gemini-3.5-flash-lite
```

Environment variables with the same names override `.env`. Check that Git ignores the file:
`git check-ignore .env` should print `.env`.

Without a key, the app still starts and all non-AI features work. AI actions explain that no key is configured.

Then test the connection. The check makes three tiny requests and never prints the key:

```bash
java -jar build/libs/mealplanner.jar llm-check
```

If a key is ever pushed or shared by mistake, revoke it in AI Studio immediately and create a new one. Deleting
the file does not remove it from Git history.

On the free tier Google allows only a few requests per minute, and each AI action makes 4–6 model calls. If the
app reports a rate limit (HTTP 429) after its automatic retry, wait a minute and try again.

## 3. Run

| Interface | Command |
|---|---|
| GUI (Swing) | `java -jar build/libs/mealplanner.jar` (or `./gradlew run`) |
| CLI (interactive) | `java -jar build/libs/mealplanner.jar cli` |
| Provider check | `java -jar build/libs/mealplanner.jar llm-check` |

Run the commands from the project folder so `.env`, `data/` and `samples/` are found. Use `--data DIR` to choose
another data directory. Only one GUI or CLI process can use a data directory at a time.

### Example CLI session

```
mealplanner> profile set --diet vegetarian --servings 2 --max-minutes 30 --exclude peanut
mealplanner> pantry set --ingredient lentils --quantity 500
mealplanner> recipe import --file samples/lentil-soup.json
mealplanner> accept
mealplanner> recipe search --text soup --max-minutes 30
mealplanner> recipe scale --id R01 --servings 5
mealplanner> reject
mealplanner> agent recommend --request "Use my lentils and keep meals under 30 minutes"
mealplanner> agent plan --start 2026-11-09 --days 7 --slots dinner --servings 2 --no-repeat
mealplanner> accept
mealplanner> agent revise --plan P01 --request "Replace Tuesday dinner with a lentil dish"
mealplanner> accept
mealplanner> agent substitute --recipe R03 --ingredient milk --request "Use a vegan alternative"
mealplanner> shopping generate --plan P01
mealplanner> status
mealplanner> quit
```

Type `help` for every command. Recipes and plans have short aliases (`R01`, `P01`) shown in lists.

## Features

| ID | Feature | Type | GUI | CLI |
|---|---|---|---|---|
| F01 | Manage dietary preferences | Deterministic | Preferences tab | `profile show/set` |
| F02 | Manage pantry inventory | Deterministic | Pantry tab | `pantry show/set/remove` |
| F03 | Create and import structured recipes | Deterministic | Recipes → New recipe / Import JSON | `recipe create`, `recipe import` |
| F04 | Search and filter recipes | Deterministic | Recipes → filters + Search | `recipe search` |
| F05 | Recommend recipes from preferences and pantry | Hybrid (AI) | Recommend tab | `agent recommend` |
| F06 | Generate a meal plan | Hybrid (AI) | Meal plans → Generate plan | `agent plan` |
| F07 | Revise a plan through natural language | Hybrid (AI) | Meal plans → Revise with AI | `agent revise` |
| F08 | Suggest and apply ingredient substitutions | Hybrid (AI) | Recipes → Substitute ingredient | `agent substitute` |
| F09 | Adjust recipe serving quantities | Deterministic | Recipes → Scale servings | `recipe scale` |
| F10 | Generate a consolidated shopping list | Deterministic | Meal plans → Shopping list | `shopping generate` |

New recipes, scaled and substituted variants, and new or revised plans appear as a **preview** first. They are
saved only when you accept them (`accept` in the CLI, **Accept and save** in the GUI). A preview becomes stale
and is refused if anything else was saved after it was created.

## Architecture and design patterns

```
PlannerGUI / PlannerCLI  ->  PlannerFacade  ->  RecipeService, PlanService, ShoppingService, ConstraintValidator
                                            ->  AgentCoordinator -> ToolRegistry (local tools)
                                                                 -> LLMClient = LoggingLLMClient -> GeminiAdapter -> Gemini API
                                            ->  StateStore = JsonStateStore (data/state.json)
```

| Pattern | Where | Problem it solves |
|---|---|---|
| Facade | `facade.PlannerFacade` | GUI and CLI share one set of operations instead of duplicating validation, agent calls and saving |
| Adapter | `agent.LLMClient` (target), `llm.GeminiAdapter` (adapter) | The Gemini request/response format stays in one class; the agent uses application types |
| Decorator | `llm.LoggingLLMClient` wraps any `LLMClient` | Sanitized timing/outcome/token logging without touching agent or provider code |
| Builder | `domain.MealPlanBuilder` (builder), `MealPlan` (product), `PlanService` (director) | Plans are only created complete, with fixed dates, slots and servings |
| Prototype | `Recipe.deepCopy()`, `MealPlan.deepCopy()`, `AppState.deepCopy()` | Scaling, substitution, revision and every save work on independent copies |

### AI agent workflow (F05–F08)

`AgentCoordinator` runs a bounded loop: at most 5 `nextStep()` calls, each requesting one local tool, then one
`finish()` call that returns JSON matching a schema. The whole request is limited to 90 seconds, with 15 seconds
per provider call. The tools are `readProfile`, `readPantry`, `searchRecipes`, `readPlan`, `listIngredients` and
`checkPlan`. They only read a snapshot of saved data or run validation, and none of them can save. Java then
checks the draft independently: every recipe or ingredient must exist and must have been returned by a tool, and
must satisfy the diet, exclusions and time limits. Plans are rebuilt through `MealPlanBuilder`. Clear errors are
shown for missing keys, timeouts, rate limits, invalid output and impossible requests (for example, too few
recipes for a no-repeat plan). Nothing is replaced with a canned response.

## Data

- `data/state.json` holds your profile, pantry, recipes and plans. It is created on first run with 21 starter
  recipes. Saves are atomic, so a failed save leaves the previous file unchanged.
- `data/logs/agent.log` records each model call: task, duration, outcome and token counts. It never stores
  prompts or keys.
- `samples/` contains `lentil-soup.json` (valid), `invalid-recipe.json` (unknown ingredient, negative quantity) and
  `malformed-recipe.json` (broken JSON), for demonstrating import and its errors.
- To start fresh, close the app and delete the `data/` folder.

The `data/` folder is ignored by Git.

## Tests

```bash
./gradlew test
```

JUnit tests cover arithmetic (scaling and shopping totals), copy independence, constraints, atomic persistence,
stale and consumed proposals, the CLI, the Gemini adapter (against a local fake server) and the agent workflow
(with a scripted test model). Full deterministic testing and agent validation are planned for Stage 3.

## Project layout

```
src/main/java/mealplanner/
  app/          Main, AppConfig (.env), AppContext (wiring), SeedData
  domain/       Recipe, MealPlan, MealPlanBuilder, AppState, Catalogue, ...
  service/      ConstraintValidator, RecipeService, PlanService, ShoppingService, drafts
  persistence/  StateStore, JsonStateStore, DataDirectoryLock
  agent/        LLMClient, AgentCoordinator, ToolRegistry, AgentPrompts, Drafts, ...
  llm/          GeminiAdapter, LoggingLLMClient, ConnectionCheck
  facade/       PlannerFacade, Proposal, Result
  ui/           TextFormat, cli/PlannerCLI, gui/PlannerGUI and panels
src/main/resources/  catalogue/ingredients.json, seed/recipes.json
src/test/java/       JUnit 4 tests
```
