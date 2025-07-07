#!/bin/bash

# ==============================================================================
# Generic Maven Project Release Script
# ==============================================================================
# 
# This script automates the complete release process for any Maven project:
# 1. Updates version numbers in both VERSION file and pom.xml
# 2. Builds the JAR file using Maven
# 3. Commits and pushes changes to git
# 4. Creates and pushes git tags
# 5. Creates GitHub releases with JAR attachments
# 
# Prerequisites:
# - Git repository with remote origin
# - GitHub CLI (gh) installed and authenticated
# - Maven or Maven wrapper (./mvnw) available
# - pom.xml file in current directory
# 
# Usage:
#   ./generic-release.sh
# 
# The script will interactively guide you through the release process.
# Works with any Maven project by automatically detecting project details.
# 
# Environment Variables (optional):
# - VERSION_FILE: Name of the version file (default: VERSION)
# - SKIP_TESTS: Skip tests during build (default: true)
# - DEFAULT_STARTING_VERSION: Version to use if no VERSION file exists (default: 1.0.0)
# - UPLOAD_RETRY_COUNT: Number of retry attempts for JAR upload (default: 3)
# - UPLOAD_TIMEOUT: Timeout in seconds for GitHub CLI operations (default: 300)
# 
# ==============================================================================

set -e  # Exit on any error

# ==============================================================================
# CONFIGURATION
# ==============================================================================

# Script configuration - can be overridden by environment variables
VERSION_FILE="${VERSION_FILE:-VERSION}"
SKIP_TESTS="${SKIP_TESTS:-true}"
DEFAULT_STARTING_VERSION="${DEFAULT_STARTING_VERSION:-1.0.0}"
UPLOAD_RETRY_COUNT="${UPLOAD_RETRY_COUNT:-3}"
UPLOAD_TIMEOUT="${UPLOAD_TIMEOUT:-300}"  # 5 minutes for large JARs

# ==============================================================================
# TERMINAL COLORS SETUP
# ==============================================================================
# Set up colored output for better readability (only if terminal supports it)
if [[ -t 1 ]] && command -v tput &> /dev/null && tput colors &> /dev/null && [[ $(tput colors) -ge 8 ]]; then
    RED='\033[0;31m'      # Red for errors
    GREEN='\033[0;32m'    # Green for success messages
    YELLOW='\033[1;33m'   # Yellow for warnings
    BLUE='\033[0;34m'     # Blue for info messages
    NC='\033[0m'          # No Color (reset)
else
    # No color support - use empty strings
    RED=''
    GREEN=''
    YELLOW=''
    BLUE=''
    NC=''
fi

# ==============================================================================
# UTILITY FUNCTIONS
# ==============================================================================

# Logging functions - all output to stderr so they don't interfere with return values
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

# ==============================================================================
# PROJECT DETECTION FUNCTIONS
# ==============================================================================

# Extract project information from pom.xml
get_project_info() {
    if [[ ! -f "pom.xml" ]]; then
        print_error "pom.xml not found in current directory"
        return 1
    fi
    
    # Extract artifactId from pom.xml (skip parent section, get project's own artifactId)
    # Look for artifactId that's not heavily indented (project level, not parent level)
    local artifact_id=$(grep "^[[:space:]]*<artifactId>" pom.xml | grep -v "^[[:space:]]\{8,\}<artifactId>" | head -1 | sed 's/.*<artifactId>\(.*\)<\/artifactId>.*/\1/' | tr -d ' ')
    
    if [[ -z "$artifact_id" ]]; then
        print_error "Could not extract artifactId from pom.xml"
        return 1
    fi
    
    echo "$artifact_id"
}

# Get the current version from pom.xml as fallback
get_pom_version() {
    if [[ ! -f "pom.xml" ]]; then
        return 1
    fi
    
    # Extract version from pom.xml (look for version tag not in parent)
    local version=$(grep -m 1 "^\s*<version>" pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d ' ')
    
    if [[ -n "$version" ]]; then
        echo "$version"
    fi
}

# ==============================================================================
# VERSION MANAGEMENT FUNCTIONS
# ==============================================================================

# Validates version format according to semantic versioning (MAJOR.MINOR.PATCH)
validate_version() {
    local version="$1"
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        print_error "Invalid version format. Please use semantic versioning (e.g., 1.0.0)"
        return 1
    fi
    return 0
}

# Increments version number according to semantic versioning rules
# Args: version (e.g., "1.2.3"), part ("major"|"minor"|"patch")
# Returns: new version string
increment_version() {
    local version="$1"
    local part="$2"
    
    # Split version into components
    IFS='.' read -r major minor patch <<< "$version"
    
    # Increment according to semantic versioning rules
    case "$part" in
        "major")
            major=$((major + 1))
            minor=0      # Reset minor and patch on major increment
            patch=0
            ;;
        "minor")
            minor=$((minor + 1))
            patch=0      # Reset patch on minor increment
            ;;
        "patch")
            patch=$((patch + 1))
            ;;
        *)
            print_error "Invalid increment type. Use: major, minor, or patch"
            return 1
      ;;
  esac
    
    echo "$major.$minor.$patch"
}

# ==============================================================================
# SYSTEM REQUIREMENTS CHECK
# ==============================================================================

# Verifies that all required tools are available before proceeding
check_requirements() {
    local missing_tools=()
    
    # Check for Git (required for version control operations)
    if ! command -v git &> /dev/null; then
        missing_tools+=("git")
    fi
    
    # Check for GitHub CLI (required for creating releases)
    if ! command -v gh &> /dev/null; then
        missing_tools+=("gh (GitHub CLI)")
    fi
    
    # Check for Maven (prefer wrapper, fallback to system maven)
    # Required for building JAR files
    if [[ ! -f "./mvnw" ]] && ! command -v mvn &> /dev/null; then
        missing_tools+=("Maven (mvn) or Maven wrapper (./mvnw)")
    fi
    
    # Exit with error if any tools are missing
    if [ ${#missing_tools[@]} -ne 0 ]; then
        print_error "Missing required tools: ${missing_tools[*]}"
        print_info "Please install the missing tools and try again."
        exit 1
    fi
}

# ==============================================================================
# GIT REPOSITORY VALIDATION
# ==============================================================================

# Checks git repository status and warns about uncommitted changes
check_git_status() {
    # Verify we're in a git repository
    if ! git rev-parse --git-dir > /dev/null 2>&1; then
        print_error "Not in a git repository"
  exit 1
fi

    # Fetch latest changes to ensure we're up to date
    print_info "Fetching latest changes from remote..."
    git fetch --all
    
    # Show current branch information
    local current_branch=$(git branch --show-current)
    print_info "Current branch: $current_branch"
    
    # Check for uncommitted changes and warn user
    if ! git diff-index --quiet HEAD --; then
        print_warning "You have uncommitted changes that will be included in the release."
        git status --short
        echo
        read -p "Do you want to continue? (y/N): " continue_choice
        if [[ ! "$continue_choice" =~ ^[Yy]$ ]]; then
            print_info "Release cancelled."
      exit 0
    fi
  fi
}

# ==============================================================================
# GITHUB RELEASE MANAGEMENT
# ==============================================================================

# Creates GitHub release with retry logic and timeout handling
# Args: version, title, notes, jar_path (optional)
# Returns: 0 on success, 1 on failure
create_github_release_with_retry() {
    local version="$1"
    local title="$2"
    local notes="$3"
    local jar_path="$4"
    
    print_info "Configuring GitHub CLI timeout to ${UPLOAD_TIMEOUT} seconds"
    
    # Configure GitHub CLI for longer timeouts (for large files)
    export GH_REQUEST_TIMEOUT="${UPLOAD_TIMEOUT}s"
    
    local attempt=1
    local max_attempts=$UPLOAD_RETRY_COUNT
    
    while [ $attempt -le $max_attempts ]; do
        print_info "Release creation attempt $attempt of $max_attempts"
        
        if [[ -n "$jar_path" && -f "$jar_path" ]]; then
            print_info "Attempting to create release with JAR attachment: $jar_path"
            local jar_size=$(du -h "$jar_path" | cut -f1)
            print_info "JAR file size: $jar_size"
            
            # Attempt release creation with JAR
            local upload_success=false
            if command -v timeout &> /dev/null; then
                print_info "Using timeout command for upload protection"
                if timeout $UPLOAD_TIMEOUT gh release create "$version" \
                    --title "$title" \
                    --notes "$notes" \
                    "$jar_path" 2>/dev/null; then
                    upload_success=true
                fi
            elif command -v gtimeout &> /dev/null; then
                print_info "Using gtimeout command for upload protection"
                if gtimeout $UPLOAD_TIMEOUT gh release create "$version" \
                    --title "$title" \
                    --notes "$notes" \
                    "$jar_path" 2>/dev/null; then
                    upload_success=true
                fi
            else
                print_warning "No timeout command available - relying on GitHub CLI timeout only"
                if gh release create "$version" \
                    --title "$title" \
                    --notes "$notes" \
                    "$jar_path" 2>/dev/null; then
                    upload_success=true
                fi
            fi
            
            if $upload_success; then
                print_success "GitHub release created successfully with JAR attachment!"
                print_info "JAR file: $(basename "$jar_path") ($jar_size)"
                return 0
            else
                local exit_code=$?
                print_warning "Attempt $attempt failed to create release with JAR (exit code: $exit_code)"
                
                if [ $attempt -eq $max_attempts ]; then
                    print_warning "All JAR upload attempts failed. Creating release without JAR..."
                    break
                else
                    print_info "Waiting 10 seconds before retry..."
                    sleep 10
                fi
            fi
        else
            # No JAR file provided, create release without attachment
            break
        fi
        
        attempt=$((attempt + 1))
    done
    
    # Final attempt: Create release without JAR
    print_info "Creating GitHub release without JAR attachment..."
    if gh release create "$version" --title "$title" --notes "$notes"; then
        print_success "GitHub release created successfully!"
        if [[ -n "$jar_path" && -f "$jar_path" ]]; then
            print_info "JAR file can be manually attached: $jar_path"
            print_info "Manual upload command: gh release upload $version '$jar_path'"
        fi
        return 0
    else
        print_error "Failed to create GitHub release"
        return 1
    fi
}

# ==============================================================================
# MAVEN PROJECT MANAGEMENT
# ==============================================================================

# Updates the version in pom.xml using Maven versions plugin
# Args: new_version (e.g., "1.2.3")
update_pom_version() {
    local new_version="$1"
    
    # Verify pom.xml exists
    if [[ ! -f "pom.xml" ]]; then
        print_error "pom.xml not found in current directory"
        return 1
    fi
    
    print_info "Updating POM version to $new_version..."
    
    # Use Maven wrapper if available, otherwise use system maven
    local maven_cmd
    if [[ -f "./mvnw" ]]; then
        maven_cmd="./mvnw"
        print_info "Using Maven wrapper (./mvnw)"
    else
        maven_cmd="mvn"
        print_info "Using system Maven"
    fi
    
    # Update version in POM using Maven versions plugin
    if $maven_cmd versions:set -DnewVersion="$new_version" -DgenerateBackupPoms=false; then
        print_success "POM version updated successfully"
        return 0
    else
        print_error "Failed to update POM version"
        return 1
    fi
}

# Builds the JAR file using Maven
# Args: version (used to locate the expected JAR file), artifact_id (project name)
# Returns: path to the built JAR file (echoed to stdout)
build_jar() {
    local version="$1"
    local artifact_id="$2"
    
    print_info "Building JAR file for project: $artifact_id"
    
    # Use Maven wrapper if available, otherwise use system maven
    local maven_cmd
    if [[ -f "./mvnw" ]]; then
        maven_cmd="./mvnw"
    else
        maven_cmd="mvn"
    fi
    
    # Build command with optional test skipping
    local build_cmd="$maven_cmd clean package"
    if [[ "$SKIP_TESTS" == "true" ]]; then
        build_cmd="$build_cmd -DskipTests"
        print_info "Running: $build_cmd (tests skipped)"
    else
        print_info "Running: $build_cmd (tests included)"
    fi
    
    if $build_cmd >&2; then
        # Find the built JAR at expected location
        local jar_file="target/${artifact_id}-${version}.jar"
        if [[ -f "$jar_file" ]]; then
            local jar_size=$(du -h "$jar_file" | cut -f1)
            print_success "JAR built successfully: $jar_file ($jar_size)"
            echo "$jar_file"  # Return the JAR path to caller
            return 0
        else
            print_error "JAR file not found at expected location: $jar_file"
            print_error "Available JAR files in target/:"
            ls -la target/*.jar >&2 2>/dev/null || print_error "No JAR files found in target/"
            return 1
        fi
    else
        print_error "Failed to build JAR"
        return 1
    fi
}

# ==============================================================================
# MAIN SCRIPT EXECUTION
# ==============================================================================

print_info "=== Generic Maven Project Release Script ==="
echo

# Step 1: Verify all required tools are available
check_requirements

# Step 2: Check git repository status and warn about uncommitted changes
check_git_status

# Step 3: Detect project information
print_info "Detecting project information..."
project_name=$(get_project_info)
if [[ $? -ne 0 || -z "$project_name" ]]; then
    print_error "Failed to detect project information from pom.xml"
    exit 1
  fi
print_info "Project name: $project_name"

# Step 4: Determine current version from VERSION file or POM
current_version=""
if [[ -f "$VERSION_FILE" ]]; then
    current_version=$(cat "$VERSION_FILE" | tr -d '\n' | tr -d ' ')
    print_info "Current version from $VERSION_FILE: $current_version"
elif [[ -n "$(get_pom_version)" ]]; then
    current_version=$(get_pom_version)
    print_info "Current version from pom.xml: $current_version"
    print_info "Creating $VERSION_FILE with current version"
    echo "$current_version" > "$VERSION_FILE"
else
    print_warning "No $VERSION_FILE found and could not extract version from pom.xml"
    print_info "Starting with version $DEFAULT_STARTING_VERSION"
    current_version="$DEFAULT_STARTING_VERSION"
    echo "$current_version" > "$VERSION_FILE"
fi

# Step 5: Present version increment options to user
echo
echo "Version options:"
echo "1) Increment patch version (${current_version} -> $(increment_version "$current_version" "patch"))"
echo "2) Increment minor version (${current_version} -> $(increment_version "$current_version" "minor"))"
echo "3) Increment major version (${current_version} -> $(increment_version "$current_version" "major"))"
echo "4) Specify custom version"
echo

read -p "Choose an option [1-4, default: 1]: " version_choice

case "$version_choice" in
    "2")
        new_version=$(increment_version "$current_version" "minor")
        ;;
    "3")
        new_version=$(increment_version "$current_version" "major")
        ;;
    "4")
        while true; do
            read -p "Enter new version (e.g., 2.1.0): " custom_version
            if validate_version "$custom_version"; then
                new_version="$custom_version"
                break
            fi
        done
        ;;
    *)
        new_version=$(increment_version "$current_version" "patch")
        ;;
esac

print_info "New version will be: $new_version"

# Step 6: Update version files
echo "$new_version" > "$VERSION_FILE"

# Update POM version to match
if ! update_pom_version "$new_version"; then
    print_error "Failed to update POM version. Release cancelled."
    echo "$current_version" > "$VERSION_FILE"
    exit 1
  fi
  
# Step 7: Get commit message and release notes from user
echo
read -p "Enter release commit message [Release v$new_version]: " commit_msg
if [[ -z "$commit_msg" ]]; then
    commit_msg="Release v$new_version"
fi

echo
read -p "Enter release notes (optional): " release_notes
if [[ -z "$release_notes" ]]; then
    release_notes="$commit_msg"
fi

# Step 8: Show what will be committed and get final confirmation
echo
print_info "Files to be committed:"
git add .
git status --short

echo
print_info "Release process will:"
print_info "  1. Update POM version to $new_version"
print_info "  2. Commit and push changes"
print_info "  3. Create and push git tag v$new_version"
print_info "  4. Build JAR file using Maven"
print_info "  5. Create GitHub release with JAR attachment (with retry logic)"
print_info "  Project: $project_name"
print_info "  JAR file: target/${project_name}-${new_version}.jar"
print_info "  Upload timeout: ${UPLOAD_TIMEOUT}s, Max retries: $UPLOAD_RETRY_COUNT"
echo
read -p "Proceed with release? (y/N): " proceed_choice
if [[ ! "$proceed_choice" =~ ^[Yy]$ ]]; then
    print_info "Release cancelled."
    # Restore VERSION file
    echo "$current_version" > "$VERSION_FILE"
    # Restore POM version
    if [[ -f "pom.xml" ]]; then
        print_info "Restoring POM version..."
        update_pom_version "$current_version" > /dev/null 2>&1 || print_warning "Could not restore POM version"
    fi
    exit 0
fi

# Step 9: Execute the release process
print_info "Committing changes..."
git commit -m "$commit_msg"

print_info "Pushing changes to remote..."
git push

# Step 10: Create and push git tag
print_info "Creating and pushing tag v$new_version..."
git tag -a "v$new_version" -m "Release v$new_version: $release_notes"
git push origin "v$new_version"

# Step 11: Build JAR file
print_info "Building application JAR..."
jar_path=$(build_jar "$new_version" "$project_name")
if [[ $? -ne 0 || -z "$jar_path" ]]; then
    print_error "Failed to build JAR. Release will continue without JAR attachment."
    jar_path=""
fi

# Step 12: Create GitHub release with JAR attachment
print_info "Creating GitHub release with retry logic..."
if ! create_github_release_with_retry "v$new_version" "Release v$new_version" "$release_notes" "$jar_path"; then
    print_warning "Failed to create GitHub release. You may need to create it manually."
    print_info "Manual commands:"
    print_info "  gh release create v$new_version --title 'Release v$new_version' --notes '$release_notes'"
    if [[ -n "$jar_path" && -f "$jar_path" ]]; then
        print_info "  gh release upload v$new_version '$jar_path'"
    fi
fi

print_success "Release process completed successfully!"
print_info "Project: $project_name"
print_info "Version: v$new_version"
print_info "Branch: $(git branch --show-current)"
print_info "Commit: $(git rev-parse HEAD)"
if [[ -n "$jar_path" && -f "$jar_path" ]]; then
    print_info "JAR file: $jar_path ($(du -h "$jar_path" | cut -f1))"
fi 