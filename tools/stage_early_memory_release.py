"""Carry forward the exact published Sky release with only the early-memory config change."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import zipfile


BASE_REVISION = "f3717e7728f14672e8bc3a832abf45c57a1dfdab"
BASE_MANIFEST = "b5ab6e18069d960126dcd9d1c71415345cd784be0591e63c836da5d2362e223c"
UNCHANGED_GAME_PATHS = {"portable-pack.json", "config/log4j2.xml", "launcher/jvm-args.txt",
                        "kubejs/client_scripts/lanmc_memory_warning_policy.js"}
ASSET_PATHS = {"config/memorysettings.json", "launcher/jvm-args.txt", "launcher/pack-owned.txt"}


def checked_bytes(path, pin, prepare):
    data = path.read_bytes()
    prepare.require(prepare.fingerprint(data) == {key: pin[key] for key in ("sizeBytes", "sha256")},
                    f"Published asset does not match its verified pin: {path}")
    return data


def retag_library(base_release, descriptor, pending, source, revision, prepare, verify_library):
    original = checked_bytes(base_release / "assets" / descriptor["name"], descriptor, prepare)
    with zipfile.ZipFile(io.BytesIO(original)) as current:
        prepare.require(current.namelist() == ["library.json", "preview.jpg"], "Unexpected published library members")
        catalog = prepare.decode_json(current.read("library.json"))
        preview = current.read("preview.jpg")
    prepare.require(catalog["revision"] == BASE_REVISION and preview == (source / "library/preview.jpg").read_bytes(),
                    "Published library differs from the checked source")
    catalog["revision"] = revision
    data = json.dumps(catalog, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
    updated = io.BytesIO()
    with zipfile.ZipFile(updated, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, contents in (("library.json", data), ("preview.jpg", preview)):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.create_system = 0
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, contents)
    asset = updated.getvalue()
    pin = prepare.fingerprint(asset)
    name = "library-" + pin["sha256"] + ".zip"
    (pending / "assets" / name).write_bytes(asset)
    result = {"name": name, "revision": revision, **pin}
    verify_library(pending, result)
    return result


def stage(base_release, out, source, revision, prepare, release, delivery, verify_library):
    manifest_bytes = (base_release / "pack-manifest.json").read_bytes()
    prepare.require(hashlib.sha256(manifest_bytes).hexdigest() == BASE_MANIFEST, "Use the exact currently published Sky manifest")
    manifest = prepare.decode_json(manifest_bytes)
    prepare.require(manifest["revision"] == BASE_REVISION and manifest["packId"] == "minecraft-13",
                    "Published base revision or pack identity differs")
    changed_source = subprocess.check_output(["git", "-C", str(source), "diff", "--name-only", BASE_REVISION, revision],
                                             text=True).splitlines()
    prepare.require(not set(changed_source) - {"README.md", "launcher/pack-owned.txt", "config/memorysettings.json",
                                                "tools/overlay.json", "tools/GenerateMemorySettingsConfig.java",
                                                "tools/MemoryWarningMenuAgent.java", "tools/stage_early_memory_release.py"},
                    "Changes beyond the reviewed Sky RAM warning policy require a full build")
    client = release.read_lock(source / release.CLIENT_LOCK, "client-only")
    author = release.read_lock(source / release.COMPLETE_LOCK, "complete")
    release.same_author(client, author)
    recipe, _, _, descriptor = release.source_policy(source, author)
    prepare.require(not recipe["patches"], "Cannot reuse the published assets with author overrides patched")
    config = prepare.memory_settings_config(recipe, source, author)
    logging = prepare.overlay_additions(recipe, source, author)
    scripts = prepare.client_scripts(recipe, source, author)
    ownership = (source / release.OWNERSHIP).read_bytes()
    owned = ("\n".join(sorted({prepare.LOGGING_CONFIG, *scripts, *config})) + "\n").encode("utf-8")
    prepare.require(ownership == owned, "Sky ownership differs from the reviewed files")
    policy = {name: prepare.fingerprint(data) for name, data in
              {**logging, **scripts, **config, release.OWNERSHIP: ownership}.items()}
    policy[release.DESCRIPTOR] = descriptor
    original = {row["path"]: {key: row[key] for key in ("sizeBytes", "sha256")} for row in manifest["files"]}
    prepare.require(prepare.MEMORY_SETTINGS_CONFIG not in original and original[release.OWNERSHIP] != policy[release.OWNERSHIP] and
                    all(original[path] == policy[path] for path in UNCHANGED_GAME_PATHS),
                    "A published file changed beyond the exact ownership update and one config addition")
    timestamp = delivery.check_source(source, revision, manifest["packId"], policy, recipe=recipe)
    excluded = prepare.excluded_dependency_files(prepare.overlay_dependency_keys(recipe, author), author["files"])
    files = {**original, **policy}
    release.verify_manifest({**manifest, "files": [{"path": path, **pin} for path, pin in files.items()]}, policy, author, excluded)

    replaced = [a for a in manifest["assets"] if release.OWNERSHIP in a["covers"]]
    prepare.require(len(replaced) == 1 and set(replaced[0]["covers"]) == {release.OWNERSHIP, "launcher/jvm-args.txt"},
                    "Published launcher asset has unexpected coverage")
    with TemporaryDirectory(prefix=".sky-early-memory-", dir=out.parent) as temporary:
        pending = Path(temporary) / "release"
        (pending / "assets").mkdir(parents=True)
        added = delivery.build_assets(source, pending / "assets", {path: files[path] for path in ASSET_PATHS})
        assets = sorted([*added, *(a for a in manifest["assets"] if a not in replaced)], key=lambda row: row["name"])
        prepare.require(sorted(path for asset in assets for path in asset["covers"]) == sorted(files),
                        "Assets do not cover the updated pack exactly once")
        library = retag_library(base_release, manifest["library"], pending, source, revision, prepare, verify_library)
        candidate = {**manifest, "revision": revision, "generatedAtUtc": timestamp, "library": library,
                     "files": [{"path": path, **pin} for path, pin in sorted(files.items())], "assets": assets}
        release.verify_manifest(candidate, policy, author, excluded)
        prepare.write_json(pending / "pack-manifest.json", candidate)
        prepare.write_json(pending / "carry-up.json", {
            "baseRevision": BASE_REVISION, "baseManifestSha256": BASE_MANIFEST, "revision": revision,
            "newAssets": added, "library": library, "manifest": prepare.fingerprint((pending / "pack-manifest.json").read_bytes()),
            "payloadFiles": len(files), "unchangedPayloadFiles": len(original) - 1,
            "reusedGameAssets": len(manifest["assets"]) - len(replaced), "replacedGameAssets": [a["name"] for a in replaced],
        })
        pending.rename(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tools", required=True, type=Path, help="LANMinecraft checkout with memorySettingsConfig support")
    parser.add_argument("--base-release", required=True, type=Path, help="Verified prior manifest and its library bundle")
    parser.add_argument("--output", required=True, type=Path, help="New directory in a registered agent run")
    parser.add_argument("--workspace", type=Path, default=Path.cwd(), help="LANMinecraft workspace that owns the agent run")
    parser.add_argument("--revision", required=True, help="Full Sky source commit")
    args = parser.parse_args()
    sys.path[:0] = [str(args.tools.resolve() / "tools/PackData/CurseForge"), str(args.tools.resolve() / "tools")]
    import prepare
    import release_overlay
    import stage_release
    from pack_library import verify_library

    stage(args.base_release.resolve(strict=True), prepare.check_output(args.output, args.workspace.resolve(strict=True)),
          Path(__file__).resolve().parents[1],
          args.revision, prepare, release_overlay, stage_release, verify_library)


if __name__ == "__main__":
    main()
