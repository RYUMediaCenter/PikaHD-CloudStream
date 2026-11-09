# PikaHD CloudStream project

This repository contains a CloudStream plugin starter project.

## Current status

- Android/Kotlin CloudStream plugin module is scaffolded.
- GitHub Actions workflow is configured to build the plugin and upload the `.cs3` artifact.
- The provider is intentionally a safe placeholder: it does not scrape PikaHD, extract unauthorized streams, bypass DRM, or link to unlicensed copies.

## Build

The GitHub Actions workflow runs on pushes to `main`, pull requests, and manual dispatch. Open the **Actions** tab and download the `cloudstream-plugin` artifact after a successful run.

## Add a catalog

Implement `AuthorizedCatalogProvider` only for a catalog/API you own or are authorized to access and distribute. Add search and metadata mapping, and implement playback only for direct media URLs you are permitted to provide.

## Project structure

- `PikaHDProvider/`: CloudStream plugin module
- `PikaHDProvider/src/main/kotlin/com/ryumediacenter/`: plugin registration and provider
- `.github/workflows/build.yml`: CI build
