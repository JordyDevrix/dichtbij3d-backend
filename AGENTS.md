# TeamLead Coordination System

This repository uses **teamlead** to coordinate multiple AI models, agents, and CLI tools simultaneously.

## Project Configuration
- **Base Branch**: `main`
- **Merge Strategy**: `squash`
- **Worktree Directory**: `.teamlead/worktrees`

## CLI Quick Reference for AI Agents
```bash
# 1. Check current tasks and locks
teamlead status
teamlead task list
teamlead lock list

# 2. Claim your task and reserve files
teamlead task claim T-1 --agent <YOUR_NAME>
teamlead lock acquire src/feature/* --agent <YOUR_NAME>

# 3. Check for conflicts before committing
teamlead conflicts

# 4. Complete task
teamlead task complete T-1 --agent <YOUR_NAME>
```

## Current Tasks
- *No tasks created yet.*

## Active Locks
- *No active locks.*
