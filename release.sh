#!/bin/bash

# set -x # Enable debugging (commented out to reduce output)

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
MAIN_BRANCH=""
DRY_RUN=false
UPLOAD_ONLY=false
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
        return 0
    else
        # print_info "Executing: $@"  # Commented out to reduce verbose output
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
        # print_info "Running $hook_name plugins..."  # Commented out to reduce verbose output
        for plugin in $(find "$hook_dir" -type f -executable | sort); do
            # print_info "Executing plugin: $plugin"  # Commented out to reduce verbose output
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
        --upload-only) UPLOAD_ONLY=true ;;
        --no-update) SKIP_UPDATE=true ;;
        *) print_error "Unknown parameter passed: $1"; exit 1 ;;
    esac
    shift
done

# ==============================================================================
# SELF-UPDATE MECHANISM
# ==============================================================================

self_update() {
    # Only update if we're in a git repository and not in dry-run mode
    if [[ "$DRY_RUN" = true || "$SKIP_UPDATE" = true ]]; then
        return 0
    fi

    # Check if we're in a git repository
    if ! git rev-parse --git-dir > /dev/null 2>&1; then
        return 0
    fi

    # Get the current script path
    local script_path="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
    
    # Check if there are remote changes
    local current_commit=$(git rev-parse HEAD 2>/dev/null)
    local remote_commit=$(git ls-remote origin HEAD 2>/dev/null | cut -f1)
    
    if [[ -n "$current_commit" && -n "$remote_commit" && "$current_commit" != "$remote_commit" ]]; then
        print_info "New version of release.sh available. Updating..."
        
        # Fetch latest changes
        if git fetch origin > /dev/null 2>&1; then
            # Get the latest version of the script
            local temp_script=$(mktemp)
            if git show "origin/HEAD:release.sh" > "$temp_script" 2>/dev/null; then
                # Make it executable
                chmod +x "$temp_script"
                
                # Replace the current script with the updated version
                if mv "$temp_script" "$script_path"; then
                    print_success "Updated release.sh to latest version"
                    
                    # Re-execute the updated script with the same arguments
                    exec "$script_path" "$@"
                else
                    print_warning "Failed to update script, continuing with current version"
                    rm -f "$temp_script"
                fi
            else
                print_warning "Failed to fetch updated script, continuing with current version"
                rm -f "$temp_script"
            fi
        else
            print_warning "Failed to fetch updates, continuing with current version"
        fi
    fi
}

get_script_version() {
    if git rev-parse --git-dir > /dev/null 2>&1; then
        local commit_hash=$(git rev-parse --short HEAD 2>/dev/null)
        local commit_date=$(git log -1 --format="%cd" --date=short 2>/dev/null)
        if [[ -n "$commit_hash" && -n "$commit_date" ]]; then
            echo "v$commit_hash ($commit_date)"
        else
            echo "unknown"
        fi
    else
        echo "standalone"
    fi
}

# Run self-update check
self_update "$@"

# ==============================================================================
# PROJECT DETECTION AND BUILD TOOL MANAGEMENT
# ==============================================================================

detect_build_tool() {
    if [[ -n "$BUILD_TOOL" ]]; then
        # print_info "Build tool specified in config: $BUILD_TOOL"  # Commented out to reduce verbose output
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
    # print_info "Detected build tool: $BUILD_TOOL"  # Commented out to reduce verbose output
}

detect_main_branch() {
    if [[ -n "$MAIN_BRANCH" ]]; then
        # print_info "Using main branch from config: $MAIN_BRANCH"  # Commented out to reduce verbose output
        return
    fi

    local remote_head=$(git remote show origin 2>/dev/null | grep 'HEAD branch' | cut -d' ' -f5)
    if [[ -n "$remote_head" && "$remote_head" != "(unknown)" ]]; then
        MAIN_BRANCH=$remote_head
        # print_info "Auto-detected main branch from remote: $MAIN_BRANCH"  # Commented out to reduce verbose output
        return
    fi

    # Check for main or master branch, treat them as equivalent
    if git show-ref --verify --quiet refs/heads/main; then
        MAIN_BRANCH="main"
    elif git show-ref --verify --quiet refs/heads/master; then
        MAIN_BRANCH="master"
    else
        print_warning "Could not auto-detect main branch. Using fallback: main. Set MAIN_BRANCH in .release.conf to override."
        MAIN_BRANCH="main"
    fi
    # print_info "Using detected/fallback main branch: $MAIN_BRANCH"  # Commented out to reduce verbose output
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
            
            # Look for the actual JAR file (Spring Boot creates repackaged JARs)
            local expected_jar="target/${artifact_id}-${version}.jar"
            if [[ -f "$expected_jar" ]]; then
                echo "$expected_jar"
            else
                # Look for any JAR file in target directory
                local found_jar=$(find target -name "*.jar" -type f | head -1)
                if [[ -n "$found_jar" ]]; then
                    echo "$found_jar"
                else
                    print_error "No JAR file found in target directory"
                    return 1
                fi
            fi
            ;;
        "gradle")
            local build_cmd="./gradlew build"
            if [[ "$SKIP_TESTS" == "true" ]]; then
                build_cmd="$build_cmd -x test"
            fi
            execute $build_cmd
            
            # Look for the actual JAR file
            local expected_jar="build/libs/${artifact_id}-${version}.jar"
            if [[ -f "$expected_jar" ]]; then
                echo "$expected_jar"
            else
                # Look for any JAR file in build/libs directory
                local found_jar=$(find build/libs -name "*.jar" -type f | head -1)
                if [[ -n "$found_jar" ]]; then
                    echo "$found_jar"
                else
                    print_error "No JAR file found in build/libs directory"
                    return 1
                fi
            fi
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
    # Treat main and master as equivalent
    if [[ "$current_branch" != "$MAIN_BRANCH" && "$current_branch" != "main" && "$current_branch" != "master" ]]; then
        print_warning "You are not on the main branch ($MAIN_BRANCH). This is the branch that will be tagged for release."
        read -p "Do you want to continue? (y/N): " continue_choice
        if [[ ! "$continue_choice" =~ ^[Yy]$ ]]; then
            print_info "Release cancelled."
            exit 0
        fi
    fi
    if ! git diff-index --quiet HEAD --; then
        print_warning "You have uncommitted changes that will be included in the release."
        git status --short
        read -p "Do you want to continue? (y/N): " continue_choice
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
        git log --pretty=format:%s
    else
        print_info "Generating changelog since tag $latest_tag"
        git log "${latest_tag}..HEAD" "--pretty=format:%s"
    fi
}

create_github_release_with_retry() {
    local version="$1"
    local title="$2"
    local notes="$3"
    local artifact_path="$4"

    print_info "Configuring GitHub CLI timeout to ${UPLOAD_TIMEOUT} seconds"
    export GH_REQUEST_TIMEOUT="${UPLOAD_TIMEOUT}s"

    local attempt=1
    local max_attempts=$UPLOAD_RETRY_COUNT

    while [ $attempt -le $max_attempts ]; do
        if [[ -n "$artifact_path" && -f "$artifact_path" ]]; then
            print_info "Release creation attempt $attempt of $max_attempts with attachment: $artifact_path"
            local artifact_size=$(du -h "$artifact_path" | cut -f1)
            print_info "Artifact file size: $artifact_size"

            local release_cmd="gh release create \"$version\" --title \"$title\" --notes \"$notes\" \"$artifact_path\""
            
            if [ "$DRY_RUN" = true ]; then
                execute "bash" "-c" "$release_cmd"
                return 0
            fi

            if bash -c "$release_cmd"; then
                print_success "GitHub release created successfully with attachment!"
                return 0
            else
                local exit_code=$?
                print_warning "Attempt $attempt failed (exit code: $exit_code)."
                if [ $attempt -eq $max_attempts ]; then
                    print_warning "All attachment upload attempts failed. Creating release without attachment..."
                    break
                else
                    print_info "Waiting 10 seconds before retry..."
                    sleep 10
                fi
            fi
        else
            # No artifact, just create release and exit loop
            break
        fi
        attempt=$((attempt + 1))
    done

    print_info "Creating GitHub release without attachment..."
    local final_cmd="gh release create \"$version\" --title \"$title\" --notes \"$notes\""
    if execute "bash" "-c" "$final_cmd"; then
        print_success "GitHub release created successfully!"
        # Always attempt to upload the artifact separately if it exists
        if [[ -n "$artifact_path" && -f "$artifact_path" ]]; then
            print_info "Attempting to upload artifact separately..."
            # Extract version number without 'v' prefix for upload function
            local version_number="${version#v}"
            if upload_artifact_to_release "$version_number" "$artifact_path"; then
                print_success "Artifact uploaded successfully after release creation!"
            else
                print_warning "Failed to upload artifact separately. Manual upload may be needed:"
                print_info "gh release upload $version '$artifact_path'"
            fi
        fi
        return 0
    else
        print_error "Failed to create GitHub release."
        return 1
    fi
}

upload_artifact_to_release() {
    local version="$1"
    local artifact_path="$2"

    print_info "=== Upload Debug Info ==="
    print_info "Version: $version"
    print_info "Artifact path: $artifact_path"
    print_info "Artifact exists: $([[ -f "$artifact_path" ]] && echo "YES" || echo "NO")"
    print_info "Artifact size: $(du -h "$artifact_path" 2>/dev/null | cut -f1 || echo "N/A")"

    if [[ -z "$artifact_path" || ! -f "$artifact_path" ]]; then
        print_error "Artifact not found at: $artifact_path"
        return 1
    fi

    # Ensure version has 'v' prefix for GitHub releases
    local release_tag="$version"
    if [[ ! "$release_tag" =~ ^v ]]; then
        release_tag="v$version"
    fi

    print_info "Release tag: $release_tag"
    print_info "Uploading artifact to release $release_tag..."
    local artifact_name=$(basename "$artifact_path")
    print_info "Artifact name: $artifact_name"
    
    # Check if release exists
    if [ "$DRY_RUN" = false ]; then
        if ! gh release view "$release_tag" > /dev/null 2>&1; then
            print_error "Release $release_tag does not exist. Cannot upload artifact."
            return 1
        fi
        
        # Check if asset already exists and delete it
        if gh release view "$release_tag" --json assets --jq ".assets[] | select(.name == \"$artifact_name\")" | grep -q name; then
            print_warning "Asset '$artifact_name' already exists on release $release_tag. Deleting it first."
            execute "gh" "release" "delete-asset" "$release_tag" "$artifact_name" "--yes"
        fi
    fi

    # Upload with retry logic
    local attempt=1
    local max_attempts=3
    
    while [ $attempt -le $max_attempts ]; do
        print_info "Upload attempt $attempt of $max_attempts..."
        
        print_info "Executing: gh release upload $release_tag $artifact_path"
        if execute "gh" "release" "upload" "$release_tag" "$artifact_path"; then
            print_success "Artifact uploaded successfully!"
            
            # Verify the upload was successful
            if [ "$DRY_RUN" = false ]; then
                print_info "Verifying upload..."
                if gh release view "$release_tag" --json assets --jq ".assets[] | select(.name == \"$artifact_name\")" | grep -q name; then
                    print_success "Upload verification successful: $artifact_name found on release $release_tag"
                else
                    print_warning "Upload verification failed: $artifact_name not found on release $release_tag"
                    print_info "Available assets on release:"
                    gh release view "$release_tag" --json assets --jq ".assets[].name" 2>/dev/null || print_info "No assets found"
                fi
            fi
            
            return 0
        else
            if [ $attempt -eq $max_attempts ]; then
                print_error "Failed to upload artifact after $max_attempts attempts."
                return 1
            else
                print_warning "Upload attempt $attempt failed. Retrying in 5 seconds..."
                sleep 5
            fi
        fi
        attempt=$((attempt + 1))
    done
}

revert_version_changes() {
    local original_version=$1
    print_warning "Reverting version changes..."
    execute "echo" "$original_version" ">" "$VERSION_FILE"
    update_version "$original_version"
}

rollback_git_changes() {
    local version=$1
    print_warning "Rolling back git changes..."
    run_plugins "on-error"
    execute "git" "tag" "-d" "v$version"
    execute "git" "push" "--delete" "origin" "v$version"
    execute "git" "reset" "--hard" "HEAD~1"
    execute "git" "push" "--force"
}

# ==============================================================================
# MAIN SCRIPT
# ==============================================================================

upload_only_main() {
    local script_version=$(get_script_version)
    print_info "=== Upload-Only Release Mode ($script_version) ==="
    if [ "$DRY_RUN" = true ]; then
        print_warning "Running in dry-run mode. No changes will be made."
    fi

    detect_build_tool
    check_requirements

    local project_name=$(get_project_info)
    if [[ -z "$project_name" ]]; then
        print_error "Could not determine project name. Please check your pom.xml or build.gradle file."
        exit 1
    fi
    print_info "Project name: $project_name"

    # Get the latest release version from GitHub to ensure VERSION file is in sync
    local latest_release_version=""
    if [ "$DRY_RUN" = false ]; then
        latest_release_version=$(gh release list --limit 1 --json tagName --jq '.[0].tagName' 2>/dev/null | sed 's/^v//')
    fi

    local current_version
    if [[ -f "$VERSION_FILE" ]]; then
        current_version=$(cat "$VERSION_FILE")
    else
        current_version=$(get_current_version)
    fi
    
    if [[ -z "$current_version" ]]; then
        print_error "Could not determine current version."
        exit 1
    fi

    # If we found a latest release version and it's different from VERSION file, update it
    if [[ -n "$latest_release_version" && "$latest_release_version" != "$current_version" ]]; then
        print_warning "VERSION file ($current_version) is out of sync with latest release ($latest_release_version)."
        print_info "Updating VERSION file to match latest release..."
        execute "echo" "$latest_release_version" ">" "$VERSION_FILE"
        current_version="$latest_release_version"
    fi

    print_info "Using current version: $current_version"

    if [ "$DRY_RUN" = false ]; then
      if ! gh release view "v$current_version" > /dev/null 2>&1; then
          print_error "Release v$current_version does not exist. Cannot upload artifact."
          print_info "Please create the release first by running a full release."
          exit 1
      fi
    fi

    local artifact_path=$(build_project "$current_version" "$project_name")
    run_plugins "post-build"

    upload_artifact_to_release "$current_version" "$artifact_path"

    print_success "Artifact for v$current_version has been successfully uploaded."
}

main() {
    if [ "$UPLOAD_ONLY" = true ]; then
        upload_only_main "$@"
        exit 0
    fi

    trap 'run_plugins "on-error"' ERR

    local script_version=$(get_script_version)
    print_info "=== Generic Project Release Script ($script_version) ==="
    if [ "$DRY_RUN" = true ]; then
        print_warning "Running in dry-run mode. No changes will be made."
    fi

    run_plugins "pre-release"

    detect_build_tool
    detect_main_branch
    check_requirements
    check_git_status

    local project_name=$(get_project_info)
    if [[ -z "$project_name" ]]; then
        print_error "Could not determine project name. Please check your pom.xml or build.gradle file."
        exit 1
    fi
    print_info "Project name: $project_name"

    # Sync VERSION file with pom.xml to prevent mismatches
    local pom_version=$(get_current_version)
    local file_version=""
    if [[ -f "$VERSION_FILE" ]]; then
        file_version=$(cat "$VERSION_FILE")
    fi
    
    local current_version="$pom_version"
    if [[ -n "$pom_version" && -n "$file_version" && "$pom_version" != "$file_version" ]]; then
        print_warning "Version mismatch detected: POM ($pom_version) vs VERSION file ($file_version)"
        print_info "Using POM version as authoritative source and updating VERSION file..."
        execute "echo" "$pom_version" ">" "$VERSION_FILE"
        current_version="$pom_version"
    elif [[ -z "$current_version" && -n "$file_version" ]]; then
        current_version="$file_version"
    elif [[ -z "$current_version" ]]; then
        print_warning "No version found. Using default: $DEFAULT_STARTING_VERSION"
        current_version=$DEFAULT_STARTING_VERSION
        execute "echo" "$current_version" ">" "$VERSION_FILE"
    fi
    
    print_info "Current version: $current_version (POM: ${pom_version:-"N/A"}, File: ${file_version:-"N/A"})"

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
    print_info "  1. Update version to $new_version"
    print_info "  2. Commit changes with message: '$commit_msg'"
    print_info "  3. Create and push tag: v$new_version"
    print_info "  4. Build project"
    print_info "  5. Create GitHub release"
    read -p "Proceed? (y/N): " proceed_choice
    if [[ ! "$proceed_choice" =~ ^[Yy]$ ]]; then
        print_info "Release cancelled."
        exit 0
    fi

    # Update VERSION file and project version
    execute "echo" "$new_version" ">" "$VERSION_FILE"
    update_version "$new_version"

    run_plugins "pre-commit"
    execute "git" "add" "."
    execute "git" "commit" "-m" "$commit_msg"
    execute "git" "push"

    execute "git" "tag" "-a" "v$new_version" "-m" "$commit_msg"
    execute "git" "push" "origin" "v$new_version"

    local artifact_path=$(build_project "$new_version" "$project_name")
    run_plugins "post-build"
    
    # Ensure VERSION file matches the actual version used in build
    local actual_version=$(get_current_version)
    if [[ -n "$actual_version" && "$actual_version" != "$new_version" ]]; then
        print_warning "POM version ($actual_version) differs from target version ($new_version). Syncing..."
        new_version="$actual_version"
        execute "echo" "$new_version" ">" "$VERSION_FILE"
    fi

    # Check if release already exists
    if [ "$DRY_RUN" = false ] && gh release view "v$new_version" > /dev/null 2>&1; then
        print_info "Release v$new_version already exists. Skipping creation and uploading JAR..."
    else
        if ! create_github_release_with_retry "v$new_version" "Release v$new_version" "$release_notes" "$artifact_path"; then
            print_error "Failed to create GitHub release."
            read -p "Rollback changes? (y/N): " rollback_choice
            if [[ "$rollback_choice" =~ ^[Yy]$ ]]; then
                rollback_git_changes "$new_version"
            fi
            exit 1
        fi
    fi

    # ALWAYS ensure the JAR is uploaded to the release (regardless of whether release was created or existed)
    print_info "=== JAR Upload Phase ==="
    if [[ -n "$artifact_path" && -f "$artifact_path" ]]; then
        print_info "JAR file path: $artifact_path"
        print_info "JAR file size: $(du -h "$artifact_path" | cut -f1)"
        
        # Force upload with multiple attempts
        local upload_success=false
        for attempt in 1 2 3; do
            print_info "JAR upload attempt $attempt of 3..."
            if upload_artifact_to_release "$new_version" "$artifact_path"; then
                print_success "JAR upload completed successfully on attempt $attempt!"
                upload_success=true
                break
            else
                print_warning "Upload attempt $attempt failed."
                if [ $attempt -lt 3 ]; then
                    print_info "Waiting 5 seconds before retry..."
                    sleep 5
                fi
            fi
        done
        
        if [ "$upload_success" = false ]; then
            print_error "All JAR upload attempts failed!"
            print_info "Manual upload command: gh release upload v$new_version '$artifact_path'"
            
            # Try one more time with direct gh command (bypassing our wrapper)
            print_info "Attempting direct upload as fallback..."
            if gh release upload "v$new_version" "$artifact_path" 2>/dev/null; then
                print_success "Direct upload succeeded!"
            else
                print_error "Direct upload also failed. Please upload manually."
            fi
        fi
    else
        print_error "JAR file not found at: $artifact_path"
        if [[ -n "$artifact_path" ]]; then
            print_info "Available files in target directory:"
            ls -la target/ 2>/dev/null || print_info "target/ directory not found"
        fi
    fi

    # Ensure VERSION file is committed and pushed
    if [[ -f "$VERSION_FILE" ]]; then
        local version_in_file=$(cat "$VERSION_FILE")
        if [[ "$version_in_file" != "$new_version" ]]; then
            print_warning "VERSION file contains $version_in_file but release was $new_version. Updating..."
            execute "echo" "$new_version" ">" "$VERSION_FILE"
            execute "git" "add" "$VERSION_FILE"
            execute "git" "commit" "-m" "Update VERSION file to $new_version"
            execute "git" "push"
        fi
    fi

    run_plugins "post-release"
    print_success "Release v$new_version successful!"
}

main "$@"