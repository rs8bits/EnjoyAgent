"""Minimal launcher. The manager, outside this container, enforces the security boundary."""
import json
import os
import sys


def main():
    operation = sys.argv[1]
    if operation == "DEMO":
        entries = [{"name": name, "sizeBytes": os.stat("/input/" + name).st_size}
                   for name in sorted(os.listdir("/input"))]
        with open("/output/summary.json", "w", encoding="utf-8") as result:
            json.dump({"message": "文件交付链路验证成功", "inputs": entries}, result, ensure_ascii=False, indent=2)
        with open("/output/result.txt", "w", encoding="utf-8") as result:
            result.write("EnjoyAgent 沙箱示例任务已完成。\n")
        print("Demo completed; produced summary.json and result.txt")
    elif operation == "PYTHON":
        # exec replaces the launcher so Docker's exit code is Python's exit code.
        os.execv(sys.executable, [sys.executable, "-I", "-u", "/code/main.py"])
    else:
        # -I excludes the script directory; load our immutable built-in module explicitly.
        import runpy
        tools = runpy.run_path('/opt/sandbox/document_tools.py')
        tools['execute'](operation)


if __name__ == "__main__":
    main()
