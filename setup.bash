#!/bin/bash
# Source this file from your ~/.bashrc or ~/.zshrc:
#   source /path/to/shell-tools/setup.bash

# Get the directory containing this script
SHELL_TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"

# Add to PATH if not already there
if [[ ":$PATH:" != *":$SHELL_TOOLS_DIR:"* ]]; then
    export PATH="$SHELL_TOOLS_DIR:$PATH"
fi

# Load all completions
if [ -d "$SHELL_TOOLS_DIR/completions" ]; then
    # Enable bash completion compatibility in zsh
    if [ -n "$ZSH_VERSION" ]; then
        autoload -Uz compinit bashcompinit
        compinit -C
        bashcompinit
    fi

    for completion in "$SHELL_TOOLS_DIR/completions"/*; do
        if [ -f "$completion" ]; then
            source "$completion"
        fi
    done
fi
