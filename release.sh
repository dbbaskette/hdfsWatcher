#!/bin/bash
# release.sh - Increment version in pom.xml, commit, push, and tag release
# Usage: ./release.sh [--release VERSION]
# Examples:
#   ./release.sh                  # Auto-increment patch version
#   ./release.sh --release 1.0.2  # Set specific version

set -e

# Parse command line arguments
RELEASE_VERSION=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --release)
      RELEASE_VERSION="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [--release VERSION]"
      echo ""
      echo "Options:"
      echo "  --release VERSION    Set specific version instead of auto-incrementing"
      echo "  -h, --help          Show this help message"
      echo ""
      echo "Examples:"
      echo "  $0                    # Auto-increment patch version"
      echo "  $0 --release 1.0.2    # Set specific version"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      echo "Use --help for usage information"
      exit 1
      ;;
  esac
done

# Validate release version format if provided
if [[ -n "$RELEASE_VERSION" ]]; then
  if [[ ! "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Error: Release version must be in format x.y.z (e.g., 1.0.2)"
    exit 1
  fi
fi

# 1. Get current branch
git_branch=$(git rev-parse --abbrev-ref HEAD)
echo "Current git branch: $git_branch"

# 2. Get the main artifactId and its version from pom.xml (not the parent)
# Auto-detect the main artifactId (first <artifactId> outside <parent> block)
# Auto-detect the main artifactId (first <artifactId> outside <parent> block)
main_artifact_id=$(awk '
  /<parent>/ {in_parent=1}
  /<\/parent>/ {in_parent=0; next}
  in_parent {next}
  /<artifactId>/ && !found {
    print $0
    found=1
  }
' pom.xml | sed -n 's:.*<artifactId>\([^<]*\)</artifactId>.*:\1:p')

if [[ -z "$main_artifact_id" ]]; then
  echo "Could not auto-detect main artifactId from pom.xml"
  exit 1
fi

# Find the <version> that comes immediately after <artifactId>$main_artifact_id</artifactId>
current_version=$(awk '/<artifactId>'"$main_artifact_id"'<\/artifactId>/{getline; while (!/<version>/) getline; gsub(/.*<version>|<\/version>.*/, ""); print $0; exit}' pom.xml)
echo "Main artifactId: $main_artifact_id"
echo "Current version: $current_version"

# 3. Determine new version
if [[ -n "$RELEASE_VERSION" ]]; then
  new_version="$RELEASE_VERSION"
  echo "Setting version to: $new_version (specified via --release flag)"
else
  # Increment patch version (x.y.z -> x.y.$((z+1)))
  IFS='.' read -r major minor patch <<< "$current_version"
  if [[ -z "$patch" ]]; then
    echo "Could not parse version from pom.xml"
    exit 1
  fi
  next_patch=$((patch+1))
  new_version="$major.$minor.$next_patch"
  echo "Bumping version to: $new_version (auto-incremented)"
fi

echo -n "Proceed with release v$new_version? [y/N]: "
read answer
if [[ ! "$answer" =~ ^[Yy]$ ]]; then
  echo "Aborted by user. No changes made."
  exit 0
fi

# 4. Update only the correct <version> tag in pom.xml (the one after <artifactId>ragui</artifactId>)
awk -v aid="$main_artifact_id" -v newver="$new_version" '
  BEGIN {found=0}
  /<artifactId>/ && $0 ~ aid {
    found=1
    print
    next
  }
  found && /<version>/ {
    sub(/<version>[^<]+<\/version>/, "<version>" newver "</version>")
    found=0
  }
  {print}
' pom.xml > pom.xml.tmp && mv pom.xml.tmp pom.xml

echo "pom.xml updated."

# 5. Git add, commit, push
git add pom.xml

git add .
read -p "Enter commit message: " commit_msg
if [ -z "$commit_msg" ]; then
  commit_msg="Release v$new_version"
fi
git commit -m "$commit_msg"
git push

echo "Code committed and pushed."

# 6. Tag the release
git tag "v$new_version"
git push origin "v$new_version"

echo "Release tagged as v$new_version and pushed."

# 7. Optionally create a GitHub release and upload the JAR

echo -n "Would you like to create a new GitHub release and upload the JAR? [y/N]: "
read gh_release_answer
if [[ "$gh_release_answer" =~ ^[Yy]$ ]]; then
  echo "Building JAR with mvn clean package..."
  mvn clean package
  JAR_PATH="target/${main_artifact_id}-$new_version.jar"
  if [[ ! -f "$JAR_PATH" ]]; then
    echo "JAR file $JAR_PATH not found! Aborting release upload."
    exit 1
  fi
  echo "Creating GitHub release v$new_version and uploading $JAR_PATH..."
  # Create release if not exists, else update assets
  if ! gh release view "v$new_version" >/dev/null 2>&1; then
    gh release create "v$new_version" "$JAR_PATH" --title "v$new_version" --notes "Release $new_version"
  else
    gh release upload "v$new_version" "$JAR_PATH" --clobber
  fi
  echo "GitHub release v$new_version created/updated with JAR."
else
  echo "Skipping GitHub release creation."
fi
echo "Release process complete."
