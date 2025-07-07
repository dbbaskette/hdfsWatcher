#!/bin/bash

# ==============================================================================
# Generic Project Release Script
# ==============================================================================
#
# This script automates the complete release process for Maven or Gradle projects:
# 1. Updates version numbers
# 2. Builds the project
# 3. Commits and pushes changes to git
# 4. Creates and pushes git tags
# 5. Creates GitHub releases with attachments
#
# ==============================================================================

set -e # Exit on any error

# ==============================================================================
# CONFIGURATION
# ==============================================================================

# Default configuration
CONFIG_FILE=".release.conf"
VERSION_FILE="VERSION"
SKIP_TESTS="true"
DEFAULT_STARTING_VERSION="1.0.0"
UPLOAD_RETRY_COUNT="3"
UPLOAD_TIMEOUT="300"
BUILD_TOOL=""
MAIN_BRANCH="main"
DRY_RUN=false
PLUGINS_DIR="plugins"

# Load from config file if it exists
if [[ -f "$CONFIG_FILE" ]]; then
    source "$CONFIG_FILE"
fi

# ==============================================================================
# TERMINAL COLORS SETUP
# ==============================================================================
if [[ -t 1 ]] && command -v tput &> /dev/null && tput colors &> /dev/null && [[ $(tput colors) -ge 8 ]]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    NC='\033[0m'
else
    RED=''
    GREEN=''
    YELLOW=''
    BLUE=''
    NC=''
fi

# ==============================================================================
# UTILITY FUNCTIONS
# ==============================================================================

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1" >&2
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1" >&2
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1" >&2
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

execute() {
    if [ "$DRY_RUN" = true ]; then
        print_info "[DRY RUN] Would execute: $@"
    else
        print_info "Executing: $@"
        "$@"
    fi
}

# ==============================================================================
# PLUGIN ENGINE
# ==============================================================================

run_plugins() {
    local hook_name=$1
    local hook_dir="$PLUGINS_DIR/$hook_name"
    if [ -d "$hook_dir" ]; then
        print_info "Running $hook_name plugins..."
        for plugin in $(find "$hook_dir" -type f -executable | sort); do
            print_info "Executing plugin: $plugin"
            execute "$plugin"
        done
    fi
}

# ==============================================================================
# PARSE ARGUMENTS
# ==============================================================================

while [[ "$#" -gt 0 ]]; do
    case $1 in
        --dry-run) DRY_RUN=true ;;
        *) print_error "Unknown parameter passed: $1"; exit 1 ;;
    esac
    shift
done

# ==============================================================================
# PROJECT DETECTION AND BUILD TOOL MANAGEMENT
# ==============================================================================

detect_build_tool() {
    if [[ -n "$BUILD_TOOL" ]]; then
        print_info "Build tool specified in config: $BUILD_TOOL"
        return
    fi

    if [[ -f "pom.xml" ]]; then
        BUILD_TOOL="maven"
    elif [[ -f "build.gradle" || -f "build.gradle.kts" ]]; then
        BUILD_TOOL="gradle"
    else
        print_error "Could not detect build tool. Please specify BUILD_TOOL in .release.conf"
        exit 1
    fi
    print_info "Detected build tool: $BUILD_TOOL"
}

get_project_info() {
    case "$BUILD_TOOL" in
        "maven")
            if ! command -v xmlstarlet &> /dev/null; then
                print_error "xmlstarlet is not installed. Please install it to continue."
                exit 1
            fi
            xmlstarlet sel -N pom=http://maven.apache.org/POM/4.0.0 -t -v "/pom:project/pom:artifactId" pom.xml
            ;;
        "gradle")
            # This is a simplification. A real implementation would need to parse the build.gradle file.
            grep -oP "rootProject.name = '\K[^']+" settings.gradle.kts
            ;;
    esac
}

get_current_version() {
    case "$BUILD_TOOL" in
        "maven")
            xmlstarlet sel -N pom=http://maven.apache.org/POM/4.0.0 -t -v "/pom:project/pom:version" pom.xml
            ;;
        "gradle")
            grep -oP "version = '\K[^']+" build.gradle.kts
            ;;
    esac
}

update_version() {
    local new_version=$1
    case "$BUILD_TOOL" in
        "maven")
            execute "mvn" "versions:set" "-DnewVersion=$new_version" "-DgenerateBackupPoms=false"
            ;;
        "gradle")
            # This is a simplification. A real implementation would need to parse and update the build.gradle file.
            execute "sed" "-i" "s/version = '.*'/version = '$new_version'/" "build.gradle.kts"
            ;;
    esac
}

build_project() {
    local version=$1
    local artifact_id=$2
    case "$BUILD_TOOL" in
        "maven")
            local build_cmd="mvn clean package"
            if [[ "$SKIP_TESTS" == "true" ]]; then
                build_cmd="$build_cmd -DskipTests"
            fi
            execute $build_cmd
            echo "target/${artifact_id}-${version}.jar"
            ;;
        "gradle")
            local build_cmd="./gradlew build"
            if [[ "$SKIP_TESTS" == "true" ]]; then
                build_cmd="$build_cmd -x test"
            fi
            execute $build_cmd
            echo "build/libs/${artifact_id}-${version}.jar"
            ;;
    esac
}

# ==============================================================================
# VERSION MANAGEMENT
# ==============================================================================

validate_version() {
    local version="$1"
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        print_error "Invalid version format. Please use semantic versioning (e.g., 1.0.0)"
        return 1
    fi
    return 0
}

increment_version() {
    local version="$1"
    local part="$2"
    IFS='.' read -r major minor patch <<< "$version"
    case "$part" in
        "major") major=$((major + 1)); minor=0; patch=0 ;;
        "minor") minor=$((minor + 1)); patch=0 ;;
        "patch") patch=$((patch + 1)) ;;
        *) print_error "Invalid increment type. Use: major, minor, or patch"; return 1 ;;
    esac
    echo "$major.$minor.$patch"
}

# ==============================================================================
# GIT AND GITHUB
# ==============================================================================

check_requirements() {
    local missing_tools=()
    if ! command -v git &> /dev/null; then missing_tools+=("git"); fi
    if ! command -v gh &> /dev/null; then missing_tools+=("gh (GitHub CLI)"); fi
    if [[ "$BUILD_TOOL" == "maven" ]] && ! command -v mvn &> /dev/null && [[ ! -f "./mvnw" ]]; then
        missing_tools+=("Maven (mvn) or Maven wrapper (./mvnw)")
    fi
    if [[ "$BUILD_TOOL" == "gradle" ]] && ! command -v gradle &> /dev/null && [[ ! -f "./gradlew" ]]; then
        missing_tools+=("Gradle or Gradle wrapper (./gradlew)")
    fi
    if [ ${#missing_tools[@]} -ne 0 ]; then
        print_error "Missing required tools: ${missing_tools[*]}"
        exit 1
    fi
}

check_git_status() {
    if ! git rev-parse --git-dir > /dev/null 2>&1; then
        print_error "Not in a git repository"
        exit 1
    fi
    execute "git" "fetch" "--all"
    local current_branch=$(git branch --show-current)
    if [[ "$current_branch" != "$MAIN_BRANCH" ]]; then
        print_warning "You are not on the main branch ($MAIN_BRANCH). Continue? (y/N)"
        read -r response
        if [[ "$response" != "y" ]]; then
            print_info "Release cancelled."
            exit 0
        fi
    fi
    if ! git diff-index --quiet HEAD --; then
        print_warning "You have uncommitted changes."
        git status --short
        read -p "Continue? (y/N): " continue_choice
        if [[ ! "$continue_choice" =~ ^[Yy]$ ]]; then
            print_info "Release cancelled."
            exit 0
        fi
    fi
}

generate_changelog() {
    local latest_tag=$(git describe --tags --abbrev=0 2>/dev/null)
    if [[ -z "$latest_tag" ]]; then
        print_warning "No previous tag found. Changelog will include all commits."
        execute "git" "log" "--pretty=format:%s"
    else
        print_info "Generating changelog since tag $latest_tag"
        execute "git" "log" "${latest_tag}..HEAD" "--pretty=format:%s"
    fi
}

create_github_release() {
    local version="$1"
    local title="$2"
    local notes="$3"
    local jar_path="$4"
    local cmd="gh release create $version --title '$title' --notes '$notes'"
    if [[ -n "$jar_path" && -f "$jar_path" ]]; then
        cmd="$cmd '$jar_path'"
    fi
    execute "bash" "-c" "$cmd"
}

rollback_changes() {
    local version=$1
    print_warning "Rolling back changes..."
    run_plugins "on-error"
    execute "git" "tag" "-d" "$version"
    execute "git" "push" "--delete" "origin" "$version"
    execute "git" "reset" "--hard" "HEAD~1"
    execute "git" "push" "--force"
}

# ==============================================================================
# MAIN SCRIPT
# ==============================================================================

main() {
    trap 'run_plugins "on-error"' ERR

    print_info "=== Generic Project Release Script ==="
    if [ "$DRY_RUN" = true ]; then
        print_warning "Running in dry-run mode. No changes will be made."
    fi

    run_plugins "pre-release"

    detect_build_tool
    check_requirements
    check_git_status

    local project_name=$(get_project_info)
    print_info "Project name: $project_name"

    local current_version
    if [[ -f "$VERSION_FILE" ]]; then
        current_version=$(cat "$VERSION_FILE")
    else
        current_version=$(get_current_version)
    fi
    
    if [[ -z "$current_version" ]]; then
        print_warning "No version found. Using default: $DEFAULT_STARTING_VERSION"
        current_version=$DEFAULT_STARTING_VERSION
    fi
    print_info "Current version: $current_version"

    echo "Version options:"
    echo "1) patch ($(increment_version "$current_version" "patch"))"
    echo "2) minor ($(increment_version "$current_version" "minor"))"
    echo "3) major ($(increment_version "$current_version" "major"))"
    echo "4) custom"
    read -p "Choose an option [1-4, default: 1]: " version_choice

    local new_version
    case "$version_choice" in
        2) new_version=$(increment_version "$current_version" "minor") ;;
        3) new_version=$(increment_version "$current_version" "major") ;;
        4) read -p "Enter new version: " custom_version
           if ! validate_version "$custom_version"; then exit 1; fi
           new_version=$custom_version ;;
        *) new_version=$(increment_version "$current_version" "patch") ;;
    esac
    print_info "New version: $new_version"

    echo "$new_version" > "$VERSION_FILE"
    update_version "$new_version"

    local changelog=$(generate_changelog)
    echo "Generated changelog:"
    echo "$changelog"
    read -p "Enter release notes (defaults to changelog): " release_notes
    if [[ -z "$release_notes" ]]; then
        release_notes=$changelog
    fi

    read -p "Enter commit message [Release v$new_version]: " commit_msg
    if [[ -z "$commit_msg" ]]; then
        commit_msg="Release v$new_version"
    fi

    print_info "Release plan:"
    print_info "  1. Commit changes with message: '$commit_msg'"
    print_info "  2. Create and push tag: v$new_version"
    print_info "  3. Build project"
    print_info "  4. Create GitHub release"
    read -p "Proceed? (y/N): " proceed_choice
    if [[ ! "$proceed_choice" =~ ^[Yy]$ ]]; then
        print_info "Release cancelled."
        rollback_changes "v$new_version"
        exit 0
    fi

    run_plugins "pre-commit"
    execute "git" "add" "."
    execute "git" "commit" "-m" "$commit_msg"
    execute "git" "push"

    execute "git" "tag" "-a" "v$new_version" "-m" "$commit_msg"
    execute "git" "push" "origin" "v$new_version"

    local jar_path=$(build_project "$new_version" "$project_name")
    run_plugins "post-build"

    if ! create_github_release "v$new_version" "Release v$new_version" "$release_notes" "$jar_path"; then
        print_error "Failed to create GitHub release."
        read -p "Rollback changes? (y/N): " rollback_choice
        if [[ "$rollback_choice" =~ ^[Yy]$ ]]; then
            rollback_changes "v$new_version"
        fi
        exit 1
    fi

    run_plugins "post-release"
    print_success "Release v$new_version successful!"
}

main "$@"
