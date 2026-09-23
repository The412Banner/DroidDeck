#!/usr/bin/env bash
# Regenerates the contributions table in README.md, between its markers, from the repository's
# pull requests: one row per pull request by anyone but the owner, and a line of totals per
# contributor. Run by the Contributions ledger workflow when a pull request is opened, merged or
# closed, and by hand with: gh auth status && .github/scripts/contributions.sh OWNER/REPO
set -euo pipefail
REPO=${1:-The412Banner/SteamDeck}
OWNER=${REPO%%/*}
ROWS=$(gh api "repos/$REPO/pulls?state=all&per_page=100" --paginate \
  --jq '.[] | select(.user.login != "'"$OWNER"'")
        | [.user.login, .number, (.title | gsub("\\|"; "\\\\|")), (.created_at[0:10]),
           (if .merged_at then "merged " + .merged_at[0:10] elif .state == "closed" then "closed" elif .draft then "draft" else "open" end),
           .html_url] | @tsv' | sort -t"$(printf '\t')" -k1,1 -k2,2n)
{
  echo "<!-- contributions:start -->"
  echo "_From the repository's pull requests; rewritten when one is opened, merged or closed._"
  echo
  echo "| Contributor | Pull request | Opened | State |"
  echo "|---|---|---|---|"
  if [ -z "$ROWS" ]; then
    echo "| — | none yet | | |"
  else
    while IFS=$'\t' read -r login num title opened state url; do
      echo "| [@$login](https://github.com/$login) | [#$num]($url) $title | $opened | $state |"
    done <<< "$ROWS"
    echo
    printf '%s\n' "$ROWS" | awk -F'\t' 'NF { t[$1]++; if ($5 ~ /^merged/) m[$1]++ } END { for (l in t) printf("- **@%s**: %d submitted, %d merged\n", l, t[l], m[l] + 0) }' | sort
  fi
  echo "<!-- contributions:end -->"
} > /tmp/contributions.md
python3 - <<'PY'
import re
s = open('README.md').read()
new = open('/tmp/contributions.md').read().rstrip('\n')
out, n = re.subn(r'<!-- contributions:start -->.*?<!-- contributions:end -->', lambda m: new, s, flags=re.S)
if n != 1:
    raise SystemExit("README.md has no contributions markers")
open('README.md', 'w').write(out)
PY
echo "README.md: contributions table rewritten"
