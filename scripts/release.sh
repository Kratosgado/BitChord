#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
GRADLE_FILE="${PROJECT_ROOT}/build.gradle.kts"

usage() {
    cat >&2 <<EOF
Usage: $(basename "$0") <version|patch|minor|major> [--remote <name>] [--force] [--no-push]

Bumps bitchordVersionName/bitchordVersionCode in build.gradle.kts, commits the
change, creates an annotated tag, and pushes both the commit and the tag to
trigger the Android release workflow.

Arguments:
  <version>       Explicit version string. "v" prefix is optional (e.g. 1.9.5 or v1.9.5).
  patch|minor|major
                  Bump the matching component of the current version in build.gradle.kts.

Options:
  --remote <name> Remote to push to (default: origin).
  --force         Overwrite the tag locally and remotely if it already exists.
  --no-push       Commit and tag locally but skip pushing.

Examples:
  $(basename "$0") 1.9.5           # set to 1.9.5
  $(basename "$0") patch           # 1.9.4 -> 1.9.5
  $(basename "$0") minor           # 1.9.4 -> 1.10.0
  $(basename "$0") major           # 1.9.4 -> 2.0.0
EOF
    exit "${1:-1}"
}

read_gradle_version() {
    grep -oP 'bitchordVersionName"\] = "\K[^"]+' "$GRADLE_FILE"
}

read_gradle_version_code() {
    grep -oP 'bitchordVersionCode"\] = \K[0-9]+' "$GRADLE_FILE"
}

bump_version() {
    local component="$1" base="$2"
    local stripped="${base#v}"
    if [[ ! "$stripped" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
        echo "Current version '${base}' is not MAJOR.MINOR.PATCH; cannot bump." >&2
        exit 1
    fi
    local major="${BASH_REMATCH[1]}" minor="${BASH_REMATCH[2]}" patch="${BASH_REMATCH[3]}"
    case "$component" in
        major) major=$((major + 1)); minor=0; patch=0 ;;
        minor) minor=$((minor + 1)); patch=0 ;;
        patch) patch=$((patch + 1)) ;;
    esac
    printf '%s.%s.%s' "$major" "$minor" "$patch"
}

version=""
remote="origin"
force=0
push=1

while (($#)); do
    case "$1" in
        -h|--help) usage 0 ;;
        --remote)
            [[ $# -ge 2 ]] || usage
            remote="$2"
            shift 2
            ;;
        --force) force=1; shift ;;
        --no-push) push=0; shift ;;
        -*) echo "Unknown option: $1" >&2; usage ;;
        *)
            if [[ -n "$version" ]]; then
                echo "Unexpected argument: $1" >&2
                usage
            fi
            version="$1"
            shift
            ;;
    esac
done

[[ -n "$version" ]] || usage

if ! git -C "$PROJECT_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    echo "Not inside a git repository." >&2
    exit 1
fi

current_version="$(read_gradle_version)"
current_code="$(read_gradle_version_code)"

case "$version" in
    patch|minor|major)
        bumped="$(bump_version "$version" "$current_version")"
        echo "Bumping ${version} from ${current_version} -> ${bumped}"
        version="$bumped"
        ;;
esac

version="${version#v}"
tag="v${version}"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid version: ${version} (must be MAJOR.MINOR.PATCH)" >&2
    exit 1
fi

if ! git -C "$PROJECT_ROOT" remote get-url "$remote" >/dev/null 2>&1; then
    echo "Remote '${remote}' is not configured." >&2
    exit 1
fi

if ! git -C "$PROJECT_ROOT" diff --quiet || ! git -C "$PROJECT_ROOT" diff --cached --quiet; then
    echo "Working tree has uncommitted changes. Commit or stash them first." >&2
    exit 1
fi

tag_exists_local=0
if git -C "$PROJECT_ROOT" rev-parse --verify --quiet "refs/tags/${tag}" >/dev/null; then
    tag_exists_local=1
fi

tag_exists_remote=0
if git -C "$PROJECT_ROOT" ls-remote --tags --exit-code "$remote" "refs/tags/${tag}" >/dev/null 2>&1; then
    tag_exists_remote=1
fi

if (( tag_exists_local || tag_exists_remote )) && (( !force )); then
    echo "Tag ${tag} already exists (local=${tag_exists_local}, remote=${tag_exists_remote}). Use --force to overwrite." >&2
    exit 1
fi

new_code=$((current_code + 1))

echo "Updating build.gradle.kts: ${current_version} (${current_code}) -> ${version} (${new_code})"

sed -i \
    -e "s|bitchordVersionName\"\] = \"${current_version}\"|bitchordVersionName\"] = \"${version}\"|" \
    -e "s|bitchordVersionCode\"\] = ${current_code}|bitchordVersionCode\"] = ${new_code}|" \
    -e 's|bitchordBetaSuffix"\] = "[^"]*"|bitchordBetaSuffix"] = ""|' \
    "$GRADLE_FILE"

branch="$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD)"

git -C "$PROJECT_ROOT" add "${GRADLE_FILE}"
git -C "$PROJECT_ROOT" commit -m "chore: bump version to ${version} (versionCode ${new_code})"

commit="$(git -C "$PROJECT_ROOT" rev-parse --short HEAD)"
echo "Committed ${commit} on ${branch}"

if (( tag_exists_local )); then
    git -C "$PROJECT_ROOT" tag -d "$tag" >/dev/null
fi
git -C "$PROJECT_ROOT" tag -a "$tag" -m "Release ${tag}"
echo "Tagged ${tag}"

if (( ! push )); then
    echo "Skipping push (--no-push). Branch and tag created locally."
    exit 0
fi

echo "Pushing ${branch} to ${remote}..."
git -C "$PROJECT_ROOT" push "$remote" "$branch"

echo "Pushing ${tag} to ${remote}..."
push_tag_args=("$remote" "refs/tags/${tag}")
if (( force )); then
    push_tag_args=("--force" "${push_tag_args[@]}")
fi
git -C "$PROJECT_ROOT" push "${push_tag_args[@]}"

repo_url="$(git -C "$PROJECT_ROOT" remote get-url "$remote" 2>/dev/null | sed -E 's#^git@github\.com:#https://github.com/#; s#\.git$##')"
if [[ "$repo_url" == https://github.com/* ]]; then
    echo "Actions:  ${repo_url}/actions"
    echo "Releases: ${repo_url}/releases"
fi
