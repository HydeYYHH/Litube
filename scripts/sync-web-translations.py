"""Synchronize Android locales, shared page labels and device fixtures."""
import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = json.loads((ROOT / "scripts/locales.json").read_text(encoding="utf-8"))["locales"]
LOCALES = {entry["tag"].lower(): entry["resource"] for entry in CATALOG}
LABELS = {
    "download": "download", "downloaded": "web_downloaded", "addToQueue": "add_to_queue",
    "openWith": "open_with", "chat": "web_chat", "about": "about", "extension": "extension",
    "downloads": "downloads", "closeChat": "web_close_chat", "quality": "player_info_quality",
    "auto": "player_quality_auto", "unavailable": "info_unavailable", "close": "close",
}


def strings(folder):
    resource = ROOT / "app/src/main/res" / folder / "strings.xml"
    values = {}
    for entry in ET.parse(resource).getroot():
        if entry.tag == "string":
            name = entry.attrib["name"]
            if name in values:
                raise ValueError(f"Duplicate string: {folder}/{name}")
            text = "".join(entry.itertext())
            if not text.strip():
                raise ValueError(f"Empty string: {folder}/{name}")
            values[name] = re.sub(r"\\([\\'\"nt])", lambda m: {
                "n": "\n", "t": "\t",
            }.get(m[1], m[1]), text)
    return values


def main(check=False):
    def write(path, content):
        data = content.encode("utf-8") if isinstance(content, str) else content
        if check:
            if not path.exists() or path.read_bytes() != data:
                raise ValueError(f"Outdated translations: {path.relative_to(ROOT)}")
        else:
            path.write_bytes(data)

    resources = {locale: strings(folder) for locale, folder in LOCALES.items()}
    if len(LOCALES) != len(CATALOG):
        raise ValueError("Duplicate locale tags")
    base = resources["en"]
    for locale, values in resources.items():
        if values.keys() != base.keys():
            raise ValueError(f"Incomplete resource catalog: {locale}")
        for key, value in values.items():
            arguments = lambda text: sorted(re.findall(r"%\d+\$[dsf]", text))
            if arguments(value) != arguments(base[key]):
                raise ValueError(f"Format arguments changed: {locale}/{key}")
    aliases = {alias.lower(): entry["tag"].lower()
               for entry in CATALOG for alias in entry.get("aliases", [])}
    for entry in CATALOG:
        source = ROOT / "app/src/main/res" / entry["resource"] / "strings.xml"
        for folder in entry.get("mirrors", []):
            target = ROOT / "app/src/main/res" / folder / "strings.xml"
            target.parent.mkdir(exist_ok=True)
            write(target, source.read_bytes())
    table = {key: {locale: values[name] for locale, values in resources.items()}
             for key, name in LABELS.items()}
    path = ROOT / "app/src/main/assets/script/core.js"
    source = path.read_text(encoding="utf-8")
    block = "  const TEXT = " + json.dumps(table, ensure_ascii=False, indent=2).replace("\n", "\n  ") + ";"
    source, count = re.subn(r"  const TEXT = \{.*?\n  \};", lambda _: block, source, count=1, flags=re.S)
    if count != 1:
        raise ValueError("Shared label table not found")
    alias_block = "  const LOCALE_ALIASES = " + json.dumps(aliases, ensure_ascii=False, indent=2).replace("\n", "\n  ") + ";"
    if "  const LOCALE_ALIASES = " in source:
        source = re.sub(r"  const LOCALE_ALIASES = \{.*?\n  \};", lambda _: alias_block, source, count=1, flags=re.S)
    else:
        source = source.replace("\n  function langKey()", "\n" + alias_block + "\n\n  function langKey()", 1)
    write(path, source)
    locale_config = '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n'
    for entry in CATALOG:
        locale_config += '    <locale android:name="' + entry.get("displayTag", entry["tag"]) + '" />\n'
    locale_config += '</locale-config>\n'
    write(ROOT / "app/src/main/res/xml/locales_config.xml", locale_config)
    device_catalog = [{"tag": entry.get("displayTag", entry["tag"]),
                       "aliases": sorted(set([entry["tag"]] + (
                           entry.get("aliases", []) if entry["tag"] == "zh-Hant" else []))),
                       "download": resources[entry["tag"].lower()]["download"],
                       "haptic_strength": resources[entry["tag"].lower()]["haptic_strength"]}
                      for entry in CATALOG]
    fixture = ROOT / "app/src/androidTest/assets/localization/catalog.json"
    fixture.parent.mkdir(exist_ok=True)
    write(fixture, json.dumps(device_catalog, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    main(check=parser.parse_args().check)
