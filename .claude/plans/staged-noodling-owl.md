# Publish to GitHub Pages

## Overview

Deploy the Vite + React SPA to GitHub Pages using a GitHub Actions workflow that builds and deploys on every push to `master`.

## Steps

### 1. Set Vite `base` path

**File:** `vite.config.ts`

GitHub Pages serves the site at `https://paradon.github.io/lxprint/`, so the `base` option must be set to `'/lxprint/'` so all asset URLs are prefixed correctly.

### 2. Add GitHub Actions deploy workflow

**File (new):** `.github/workflows/deploy.yml`

Create a workflow that:
- Triggers on push to `master`
- Installs dependencies (`npm ci`)
- Builds the project (`npm run build`)
- Deploys the `dist/` folder using the `actions/deploy-pages` action

Uses the standard `actions/configure-pages`, `actions/upload-pages-artifact`, and `actions/deploy-pages` actions.

### 3. Enable GitHub Pages in repo settings

After pushing the workflow, go to **Settings > Pages** in the GitHub repo and set the source to **GitHub Actions** (instead of "Deploy from a branch").

Alternatively this can be done via CLI: `gh api repos/paradon/lxprint/pages -X PUT -f build_type=workflow`.

## Verification

1. Push changes to `master`
2. Check the Actions tab for a successful deployment
3. Visit `https://paradon.github.io/lxprint/` and verify the app loads
