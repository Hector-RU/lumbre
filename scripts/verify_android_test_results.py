"""Reject missing, empty or failed connected-device JUnit reports in CI."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(directory: Path) -> None:
    reports = sorted(directory.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"No connected-device JUnit reports found in {directory}")
    executed = skipped = 0
    for report in reports:
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        if not suites:
            raise ValueError(f"No test suites in {report}")
        for suite in suites:
            if int(suite.get("failures", "0")) or int(suite.get("errors", "0")):
                raise ValueError(f"Failed connected-device suite in {report}")
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"Failed test {case.get('name')} in {report}")
            if case.find("skipped") is not None:
                skipped += 1
            else:
                executed += 1
    if not executed:
        raise ValueError("No connected-device tests executed successfully")
    print(f"Connected-device results: {executed} passed, {skipped} skipped")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]) if len(sys.argv) > 1 else Path("app/build/outputs/androidTest-results/connected"))
    except (ValueError, OSError, ET.ParseError) as error:
        sys.exit(str(error))
