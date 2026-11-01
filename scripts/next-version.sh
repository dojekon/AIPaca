#!/usr/bin/env bash
set -euo pipefail

BASE_VERSION="${BASE_VERSION:-0.1.0}"

last_tag="$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n1)"

if [ -z "$last_tag" ]; then
  version="$BASE_VERSION"
  range="HEAD"
else
  IFS=. read -r major minor patch <<< "${last_tag#v}"
  range="$last_tag..HEAD"
  messages="$(git log --format=%B "$range")"
  if printf '%s' "$messages" | grep -qiE '#major|BREAKING CHANGE'; then
    version="$((major + 1)).0.0"
  elif printf '%s' "$messages" | grep -qiE '#minor'; then
    version="$major.$((minor + 1)).0"
  else
    version="$major.$minor.$((patch + 1))"
  fi
fi

code="$(git rev-list --count HEAD)"

echo "version=$version"
echo "code=$code"
echo "tag=v$version"
echo "previous=$last_tag"
