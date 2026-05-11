# shell-tools

A collection of shell utilities.

## Installation

### Bash

#### Option 1: Using ~/.bashrc.d (recommended)

If your `.bashrc` sources files from `~/.bashrc.d`, just symlink the setup script:

```bash
mkdir -p ~/.bashrc.d
ln -s /path/to/shell-tools/setup.bash ~/.bashrc.d/shell-tools
```

Then restart your shell or run `source ~/.bashrc`.

#### Option 2: Direct source

Alternatively, add one line to your `~/.bashrc`:

```bash
source /path/to/shell-tools/setup.bash
```

### Zsh

#### Option 1: Direct source (recommended)

Add one line to your `~/.zshrc`:

```zsh
source /path/to/shell-tools/setup.bash
```

#### Option 2: Oh-My-Zsh custom directory

If using oh-my-zsh, you can alternatively symlink to the custom directory:

```bash
ln -s /path/to/shell-tools/setup.bash ~/.oh-my-zsh/custom/shell-tools.zsh
```

Files in `~/.oh-my-zsh/custom/` ending in `.zsh` are automatically sourced. Then restart your shell.

---

The setup script will:
- Add shell-tools to your PATH
- Enable bash completion for all included tools

When you `git pull` updates, everything auto-updates.
