# Roadmap

## Overview

This document outlines the development roadmap for fcitx5-android, including planned features, improvements, and technical goals.

---

## Plugin System

### Multi-Source Plugin Discovery

**Status**: Planned

**Description**:
Enable detection of plugins from various fcitx5-android forks and variants. Currently, only plugins with matching `applicationId` prefix can be discovered. The goal is to support multiple fork sources.

**Implementation**:
- Maintain a list of known fork application IDs
- Query multiple `plugin.MANIFEST` intents during plugin discovery
- Allow easy addition of new fork IDs when needed

**Benefits**:
- Support ecosystem of fcitx5-android variants
- Enable users to install plugins from different sources
- No modification required for existing plugins

### Plugin Conflict Detection

**Status**: Planned

**Description**:
Detect and prevent conflicts when multiple plugins provide the same addon (e.g., multiple `rime` engines from different forks).

**Detection Criteria**:
- Addon `uniqueName` conflicts (e.g., two plugins both providing `rime`)
- Shared library filename conflicts (e.g., two `libfcitx5-rime.so`)

**Implementation**:
- Parse plugin `addon.conf` files to extract addon names
- Build conflict graph at plugin discovery time
- Prevent enabling conflicting plugins simultaneously
- Show conflict information in plugin UI

**User Experience**:
- When user attempts to enable a conflicting plugin, show warning dialog
- Display which plugin conflicts with the selected one
- Allow user to disable the existing plugin first, then enable the new one

---

## Input Method Improvements

### Enhanced Keyboard Customization

**Status**: Planned

**Planned Features**:
- Custom key press animations
- Adjustable key press feedback (haptic, sound)
- Per-app keyboard layouts
- Custom theme support with live preview

### Cloud Sync

**Status**: Planned

**Description**:
Allow users to sync their configuration across devices using cloud storage providers.

**Implementation**:
- Support common cloud services (Google Drive, Dropbox, etc.)
- Selective sync (choose which configs to sync)
- Conflict resolution for simultaneous edits

---

## Performance Optimization

### Startup Time Reduction

**Status**: Planned

**Goals**:
- Reduce cold start time by 30%
- Optimize plugin loading sequence
- Implement lazy loading for non-essential components

### Memory Usage

**Status**: Planned

**Goals**:
- Reduce memory footprint by 20%
- Implement memory pressure monitoring
- Optimize candidate window rendering

---

## Documentation

### Developer Guide

**Status**: Planned

**Topics**:
- Plugin development tutorial
- Addon API documentation
- Build system overview
- Debugging techniques

### User Guide

**Status**: Planned

**Topics**:
- Installation and setup
- Plugin management
- Keyboard customization
- Troubleshooting common issues

---

## Testing

### Automated Testing

**Status**: Planned

**Goals**:
- Unit test coverage > 80% for core modules
- Integration tests for plugin system
- UI automation tests for critical user flows
- Performance benchmarking suite

---

## Timeline

| Feature | Target Version | Status |
|---------|----------------|--------|
| Plugin enable/disable | v1.0 | Completed |
| Multi-source plugin discovery | v1.1 | Planned |
| Plugin conflict detection | v1.1 | Planned |
| Enhanced keyboard customization | v1.2 | Planned |
| Cloud sync | v1.3 | Planned |
| Startup optimization | v1.2 | Planned |

---

## Contributing

Contributions are welcome! Please refer to the project guidelines and submit pull requests for any improvements or new features.