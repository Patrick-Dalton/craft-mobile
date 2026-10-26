"""Turns a Craft app's desktop crate into an Android library, in a CI checkout.

    python androidify.py <upstream checkout> <app id>

- The workspace patches rfd and arboard with the Android shims.
- `apps/<app>` gets a `cdylib` named `craft_app` whose root, `src/android_lib.rs`, is a copy of
  `main.rs` plus an `android_main` that runs `main()` through craft-android-glue.
- `eframe::run_native(` becomes `craft_android_glue::run_native(` in the crate's sources.
- The crate's lints stop inheriting the workspace's `unsafe_code = "forbid"`, which rejects
  `#[no_mangle]` (the entry point Android looks up).

Nothing is committed: this only edits the CI's copy of the upstream code.
"""

import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1]).resolve()
app = sys.argv[2]
shared = pathlib.Path(__file__).resolve().parent.parent
crate = root / "apps" / app


def rel(path: pathlib.Path, start: pathlib.Path) -> str:
    import os

    return pathlib.Path(os.path.relpath(path, start)).as_posix()


def locked_version(name: str) -> str:
    lock = (root / "Cargo.lock").read_text(encoding="utf-8")
    m = re.search(r'\[\[package\]\]\nname = "' + re.escape(name) + r'"\nversion = "([^"]+)"', lock)
    if not m:
        sys.exit(f"{name} is not in Cargo.lock")
    return m.group(1)


# 1. Workspace: the shims.
ws = root / "Cargo.toml"
text = ws.read_text(encoding="utf-8")
shims = (
    f'rfd = {{ path = "{rel(shared / "shims" / "rfd", root)}" }}\n'
    f'arboard = {{ path = "{rel(shared / "shims" / "arboard", root)}" }}\n'
)
if "[patch.crates-io]" in text:
    text = text.replace("[patch.crates-io]\n", "[patch.crates-io]\n" + shims, 1)
else:
    text += "\n[patch.crates-io]\n" + shims
ws.write_text(text, encoding="utf-8")

# 2. The glue: exactly the eframe and winit this workspace locks.
glue_toml = shared / "glue" / "Cargo.toml"
g = glue_toml.read_text(encoding="utf-8")
g = re.sub(r'eframe = \{ version = "[^"]+"', f'eframe = {{ version = "={locked_version("eframe")}"', g)
g = re.sub(r'winit = \{ version = "[^"]+"', f'winit = {{ version = "={locked_version("winit")}"', g)
glue_toml.write_text(g, encoding="utf-8")

# 3. The app crate's manifest.
manifest = crate / "Cargo.toml"
m = manifest.read_text(encoding="utf-8")
m = re.sub(r"\[lints\]\s*\nworkspace\s*=\s*true\s*\n", "", m)
m += f"""
[lib]
name = "craft_app"
crate-type = ["cdylib"]
path = "src/android_lib.rs"

[target.'cfg(target_os = "android")'.dependencies]
craft-android-glue = {{ path = "{rel(shared / "glue", crate)}" }}
"""
manifest.write_text(m, encoding="utf-8")

# 4. Sources: run_native through the glue.
for src in (crate / "src").rglob("*.rs"):
    s = src.read_text(encoding="utf-8")
    t = s.replace("eframe::run_native(", "craft_android_glue::run_native(")
    if t != s:
        src.write_text(t, encoding="utf-8")

# 5. The library root.
main = (crate / "src" / "main.rs").read_text(encoding="utf-8")
# `forbid(unsafe_code)` can't be lifted by an `allow`: take unsafe_code out of crate-level lists.
def drop_unsafe_code(m: re.Match) -> str:
    lints = [l.strip() for l in m.group(2).split(",") if l.strip() and l.strip() != "unsafe_code"]
    return f"#![{m.group(1)}({', '.join(lints)})]" if lints else ""


main = re.sub(r"^#!\[(forbid|deny)\(([^)]*)\)\]", drop_unsafe_code, main, flags=re.M)
main += """

/// Android's entry point (GameActivity): the desktop `main()`, with the activity behind eframe.
#[cfg(target_os = "android")]
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
fn android_main(app: craft_android_glue::AndroidApp) {
    craft_android_glue::start(app, env!("CARGO_PKG_NAME"));
    let _ = main();
}
"""
(crate / "src" / "android_lib.rs").write_text(main, encoding="utf-8")
print(f"androidified apps/{app}: eframe {locked_version('eframe')}, winit {locked_version('winit')}")
