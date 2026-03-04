#!/usr/bin/env python3
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

DUMP_PATH = "/sdcard/window_dump.xml"


def dump_root():
    subprocess.run(f"adb shell uiautomator dump {DUMP_PATH} >/dev/null", shell=True, check=True)
    xml = subprocess.check_output(f"adb exec-out cat {DUMP_PATH}", shell=True, text=True)
    return ET.fromstring(xml)


def iter_nodes(root):
    for node in root.iter("node"):
        yield node


def center_from_bounds(bounds):
    nums = list(map(int, re.findall(r"\d+", bounds)))
    if len(nums) != 4:
        raise ValueError(f"Invalid bounds: {bounds}")
    return (nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2


def matches(node, args):
    a = node.attrib
    if args.id is not None and a.get("resource-id", "") != args.id:
        return False
    if args.text is not None and a.get("text", "") != args.text:
        return False
    if args.contains_text is not None and args.contains_text not in a.get("text", ""):
        return False
    if args.desc is not None and a.get("content-desc", "") != args.desc:
        return False
    if args.contains_desc is not None and args.contains_desc not in a.get("content-desc", ""):
        return False
    if args.cls is not None and a.get("class", "") != args.cls:
        return False
    if args.clickable and a.get("clickable", "") != "true":
        return False
    return True


def find_matches(args):
    root = dump_root()
    return [n for n in iter_nodes(root) if matches(n, args)]


def cmd_list(_args):
    root = dump_root()
    for node in iter_nodes(root):
        a = node.attrib
        rid = a.get("resource-id", "")
        text = a.get("text", "")
        desc = a.get("content-desc", "")
        if rid or text or desc:
            print(
                f"{a.get('class','')} | id={rid} | text={text} | desc={desc} "
                f"| clickable={a.get('clickable','')} | bounds={a.get('bounds','')}"
            )


def cmd_exists(args):
    print("1" if find_matches(args) else "0")


def cmd_tap(args):
    deadline = time.time() + args.timeout
    while True:
        matches_found = find_matches(args)
        if matches_found:
            node = matches_found[args.index]
            x, y = center_from_bounds(node.attrib["bounds"])
            subprocess.run(f"adb shell input tap {x} {y}", shell=True, check=True)
            print(f"tapped x={x} y={y}")
            return
        if time.time() >= deadline:
            print("no match", file=sys.stderr)
            sys.exit(2)
        time.sleep(0.3)


def add_match_args(parser):
    parser.add_argument("--id")
    parser.add_argument("--text")
    parser.add_argument("--contains-text")
    parser.add_argument("--desc")
    parser.add_argument("--contains-desc")
    parser.add_argument("--cls")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_list = sub.add_parser("list")
    p_list.set_defaults(func=cmd_list)

    p_exists = sub.add_parser("exists")
    add_match_args(p_exists)
    p_exists.set_defaults(func=cmd_exists)

    p_tap = sub.add_parser("tap")
    add_match_args(p_tap)
    p_tap.add_argument("--clickable", action="store_true")
    p_tap.add_argument("--index", type=int, default=0)
    p_tap.add_argument("--timeout", type=float, default=2.0)
    p_tap.set_defaults(func=cmd_tap)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

