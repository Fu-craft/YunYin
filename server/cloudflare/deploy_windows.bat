@echo off
REM ---------------------------------------------------------------------------
REM  One-click deploy of the listen-together relay to Cloudflare Workers.
REM
REM  DOUBLE-CLICK THIS FILE. It does everything:
REM    1. opens your browser so you can log in to Cloudflare (once)
REM    2. creates the Worker, the Durable Object namespace and the binding
REM    3. asks you for an access token
REM    4. prints your service address
REM
REM  Why this instead of the dashboard: a Durable Object must be declared in the
REM  deployment (a migration). The command line does that automatically; the
REM  dashboard makes you create the namespace first and bind it by hand, which is
REM  exactly the step that is easy to miss (and which leaves /health working while
REM  room creation fails with error 1101).
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

echo ============================================
echo  Listen-together relay - deploy to Cloudflare
echo ============================================
echo.

where node >nul 2>&1
if errorlevel 1 (
  echo [X] Node.js not found. Install it first: https://nodejs.org/
  pause
  exit /b 1
)

echo --- Step 1/3: log in to Cloudflare (a browser window will open) ---
echo     Already logged in? This finishes immediately.
echo.
call npx --yes wrangler@latest login
if errorlevel 1 (
  echo.
  echo [X] Login failed. Try again, or run manually: npx wrangler login
  pause
  exit /b 1
)

echo.
echo --- Step 2/3: deploy (creates the Worker and the Durable Object) ---
echo.
call npx --yes wrangler@latest deploy
if errorlevel 1 (
  echo.
  echo [X] Deploy failed. Send this window's output to the developer.
  pause
  exit /b 1
)

echo.
echo --- Step 3/3: set an access token ---
echo     Pick any string you like, for example: yunyin-8f3k2
echo     Write it down - it goes into the app's local.properties later.
echo.
call npx --yes wrangler@latest secret put RELAY_TOKEN
if errorlevel 1 (
  echo.
  echo [X] Setting the token failed. Run manually: npx wrangler secret put RELAY_TOKEN
  pause
  exit /b 1
)

echo.
echo ============================================
echo  Done.
echo ============================================
echo.
echo Your address looks like this, in the deploy output above:
echo   https://yunyin-together.YOUR-SUBDOMAIN.workers.dev
echo.
echo Send that address and the token to the developer and it can be wired
echo into the app.
echo.
pause
