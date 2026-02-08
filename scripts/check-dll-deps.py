#!/usr/bin/env python3
"""Check DLL dependencies for lldb-dap on Windows.

Finds dumpbin (from Visual Studio) and lists the dependencies of
lldb-dap.exe and liblldb.dll, marking each as 'bundled', 'found', or
'MISSING'. This helps diagnose STATUS_DLL_NOT_FOUND (exit code
-1073741515 / 0xC0000135) failures on Windows CI.

Usage: python scripts/check-dll-deps.py prebuilts/lldb/win32-x64/bin
"""

import ctypes.util
import glob
import os
import subprocess
import sys


def find_dumpbin():
    """Locate dumpbin.exe from the Visual Studio installation."""
    pattern = os.path.join(
        os.environ.get("ProgramFiles", r"C:\Program Files"),
        "Microsoft Visual Studio", "**", "Hostx64", "x64", "dumpbin.exe"
    )
    matches = glob.glob(pattern, recursive=True)
    return matches[0] if matches else None


def get_dependents(dumpbin, binary_path):
    """Run dumpbin /dependents and return the list of DLL names."""
    result = subprocess.run(
        [dumpbin, "/dependents", binary_path],
        capture_output=True, text=True
    )
    # dumpbin output has DLL names as indented lines ending in .dll
    return [
        line.strip() for line in result.stdout.splitlines()
        if line.strip().lower().endswith(".dll")
    ]


def check_dll(dll_name, bin_dir):
    """Check if a DLL is bundled, on PATH, or missing."""
    # Check if bundled alongside the binary
    if os.path.exists(os.path.join(bin_dir, dll_name)):
        return "bundled"
    # Check if findable via system search (PATH, system dirs)
    if ctypes.util.find_library(dll_name.replace(".dll", "")):
        return "found"
    # Try loading directly (covers cases find_library misses)
    try:
        ctypes.WinDLL(dll_name)
        return "found"
    except OSError:
        pass
    return "MISSING"


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <bin-directory>", file=sys.stderr)
        sys.exit(1)

    bin_dir = sys.argv[1]
    dumpbin = find_dumpbin()

    if not dumpbin:
        print("dumpbin.exe not found — cannot check dependencies")
        print("Trying direct load test of liblldb.dll instead...")
        liblldb_path = os.path.join(bin_dir, "liblldb.dll")
        try:
            # Add bin_dir to DLL search path
            os.add_dll_directory(bin_dir)
            os.environ["PATH"] = bin_dir + ";" + os.environ.get("PATH", "")
            ctypes.WinDLL(liblldb_path)
            print("liblldb.dll loaded successfully")
        except OSError as e:
            print(f"liblldb.dll FAILED to load: {e}")
        return

    for binary in ["lldb-dap.exe", "liblldb.dll"]:
        binary_path = os.path.join(bin_dir, binary)
        if not os.path.exists(binary_path):
            print(f"{binary}: not found in {bin_dir}")
            continue

        deps = get_dependents(dumpbin, binary_path)
        print(f"--- {binary} ({len(deps)} dependencies) ---")
        missing = []
        for dep in deps:
            status = check_dll(dep, bin_dir)
            print(f"  {dep}: {status}")
            if status == "MISSING":
                missing.append(dep)

        if missing:
            print(f"  ** {len(missing)} MISSING: {', '.join(missing)}")
        else:
            print(f"  All dependencies satisfied.")


if __name__ == "__main__":
    main()
