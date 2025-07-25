#!/bin/bash

# ==============================================================================
# RELEASE SCRIPT WRAPPER
# ==============================================================================
# This is a minimal wrapper that downloads and executes the latest release script
# All functionality is contained in .release-exec

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# ==============================================================================
# SELF-UPDATE MECHANISM
# ==============================================================================

download_latest_script() {
    local exec_script="$(pwd)/.release-exec"
    local repo_url="https://github.com/dbbaskette/release"
    
    print_info "Downloading latest version from $repo_url..."
    
    # Download using curl
    if curl -sL "$repo_url/raw/main/.release-exec" > "$exec_script" 2>/dev/null; then
        # Check if the file was actually downloaded (not empty)
        if [[ -s "$exec_script" ]]; then
            # Make it executable
            chmod +x "$exec_script"
            print_success "Downloaded latest version as $exec_script"
            return 0
        else
            print_error "Downloaded file is empty"
            rm -f "$exec_script"
            return 1
        fi
    else
        print_error "Failed to download from GitHub"
        return 1
    fi
}

# ==============================================================================
# MAIN EXECUTION
# ==============================================================================

main() {
    local exec_script="$(pwd)/.release-exec"
    
    # Check if we already have the execution script
    if [[ -f "$exec_script" ]]; then
        print_info "Found existing script: $exec_script"
        print_info "Checking permissions..."
        ls -la "$exec_script"
        print_info "Executing latest version..."
        exec "$exec_script" "$@"
    elif [[ -f ".release-exec" ]]; then
        print_info "Found script in current directory: .release-exec"
        print_info "Checking permissions..."
        ls -la ".release-exec"
        print_info "Executing latest version..."
        exec "./.release-exec" "$@"
    else
        print_info "No existing script found. Downloading latest version..."
        if download_latest_script; then
            print_info "Checking permissions..."
            ls -la "$exec_script"
            print_info "Executing downloaded version..."
            exec "$exec_script" "$@"
        else
            print_error "Failed to download script. Cannot continue."
            exit 1
        fi
    fi
}

main "$@"