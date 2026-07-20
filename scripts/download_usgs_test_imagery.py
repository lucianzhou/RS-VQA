#!/usr/bin/env python3
"""Create a local, provenance-recorded imagery pack for RS-VQA engineering tests.

This script deliberately downloads no labels and makes no VQA quality claim.
It fetches small JPEG exports from the public USGS National Map imagery service,
validates the payload, and writes a CSV manifest alongside the ignored images.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import sys
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


SERVICE_ENDPOINT = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/export"
SOURCE_SERVICE = "USGS National Map USGSImageryOnly"
SOURCE_ATTRIBUTION = "USDA, USGS The National Map: Orthoimagery"
USER_AGENT = "RS-VQA-local-engineering-test-image-pack/0.1"
GRID_SIZE = 4


@dataclass(frozen=True)
class Location:
    identifier: str
    visual_theme: str
    longitude: float
    latitude: float
    tile_span_degrees: float


LOCATIONS: tuple[Location, ...] = (
    Location("san_francisco_coastal_urban", "coastal urban, road, water, park", -122.500, 37.770, 0.012),
    Location("new_york_dense_urban", "dense urban, road, building, water", -74.010, 40.705, 0.010),
    Location("miami_coastal_urban", "coastal urban, water, vegetation", -80.220, 25.790, 0.014),
    Location("phoenix_desert_urban", "desert urban, road, building", -112.080, 33.445, 0.015),
    Location("houston_suburban", "suburban building, road, water", -95.350, 29.770, 0.016),
    Location("denver_mountain_urban", "urban, road, vegetation, dry terrain", -104.990, 39.745, 0.014),
    Location("central_valley_farmland", "farmland, irrigation, road", -121.800, 37.300, 0.022),
    Location("iowa_farmland", "farmland, road, water", -93.600, 41.900, 0.022),
    Location("kansas_farmland", "farmland, road, sparse settlement", -97.340, 38.960, 0.024),
    Location("oregon_forest", "forest, water, road", -123.050, 44.700, 0.022),
    Location("appalachian_forest", "forest, road, rural settlement", -81.500, 37.100, 0.022),
    Location("everglades_wetland", "wetland, water, vegetation", -80.800, 25.500, 0.024),
)


@dataclass(frozen=True)
class PlannedImage:
    relative_path: Path
    pack: str
    location: Location
    bbox: tuple[float, float, float, float]
    pixel_size: int


def build_bbox(longitude: float, latitude: float, span: float) -> tuple[float, float, float, float]:
    half_span = span / 2
    return (
        longitude - half_span,
        latitude - half_span,
        longitude + half_span,
        latitude + half_span,
    )


def planned_images() -> list[PlannedImage]:
    plan: list[PlannedImage] = []
    for location in LOCATIONS:
        plan.append(
            PlannedImage(
                relative_path=Path("single") / f"{location.identifier}.jpg",
                pack="single",
                location=location,
                bbox=build_bbox(location.longitude, location.latitude, location.tile_span_degrees * GRID_SIZE),
                pixel_size=1024,
            )
        )
        for row in range(GRID_SIZE):
            for column in range(GRID_SIZE):
                longitude = location.longitude + (column - (GRID_SIZE - 1) / 2) * location.tile_span_degrees
                latitude = location.latitude + ((GRID_SIZE - 1) / 2 - row) * location.tile_span_degrees
                plan.append(
                    PlannedImage(
                        relative_path=Path("batch") / f"{location.identifier}_r{row + 1}_c{column + 1}.jpg",
                        pack="batch",
                        location=location,
                        bbox=build_bbox(longitude, latitude, location.tile_span_degrees),
                        pixel_size=512,
                    )
                )
    return plan


def request_url(image: PlannedImage) -> str:
    west, south, east, north = image.bbox
    query = urlencode(
        {
            "bbox": f"{west:.6f},{south:.6f},{east:.6f},{north:.6f}",
            "bboxSR": "4326",
            "size": f"{image.pixel_size},{image.pixel_size}",
            "imageSR": "4326",
            "format": "jpg",
            "f": "image",
        }
    )
    return f"{SERVICE_ENDPOINT}?{query}"


def bbox_text(bbox: tuple[float, float, float, float]) -> str:
    return ",".join(f"{value:.6f}" for value in bbox)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file_handle:
        for chunk in iter(lambda: file_handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_valid_jpeg(path: Path) -> bool:
    try:
        with path.open("rb") as file_handle:
            return file_handle.read(3) == b"\xff\xd8\xff"
    except OSError:
        return False


def download_image(url: str, destination: Path) -> tuple[str, str]:
    temporary_path = destination.with_suffix(destination.suffix + ".part")
    request = Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(1, 4):
        try:
            with urlopen(request, timeout=45) as response, temporary_path.open("wb") as file_handle:
                content_type = response.headers.get_content_type()
                if content_type not in {"image/jpeg", "image/jpg"}:
                    raise ValueError(f"unexpected content type: {content_type}")
                while chunk := response.read(1024 * 1024):
                    file_handle.write(chunk)
            if not is_valid_jpeg(temporary_path):
                raise ValueError("response is not a JPEG payload")
            temporary_path.replace(destination)
            return "downloaded", ""
        except (HTTPError, URLError, TimeoutError, ValueError, OSError) as error:
            temporary_path.unlink(missing_ok=True)
            if attempt == 3:
                return "failed", str(error)
            time.sleep(attempt)
    return "failed", "download retries exhausted"


def manifest_row(image: PlannedImage, destination: Path, status: str, note: str) -> dict[str, str | int]:
    exists_and_valid = is_valid_jpeg(destination)
    downloaded_utc = ""
    if exists_and_valid:
        downloaded_utc = datetime.fromtimestamp(destination.stat().st_mtime, UTC).isoformat()
    return {
        "relative_path": image.relative_path.as_posix(),
        "pack": image.pack,
        "location_id": image.location.identifier,
        "visual_theme_not_ground_truth": image.location.visual_theme,
        "bbox_wgs84_west_south_east_north": bbox_text(image.bbox),
        "pixel_size": image.pixel_size,
        "source_service": SOURCE_SERVICE,
        "source_attribution": SOURCE_ATTRIBUTION,
        "source_endpoint": SERVICE_ENDPOINT,
        "sha256": sha256_file(destination) if exists_and_valid else "",
        "bytes": destination.stat().st_size if exists_and_valid else "",
        "status": status,
        "downloaded_utc": downloaded_utc,
        "note": note,
    }


def write_manifest(path: Path, rows: list[dict[str, str | int]]) -> None:
    fieldnames = [
        "relative_path",
        "pack",
        "location_id",
        "visual_theme_not_ground_truth",
        "bbox_wgs84_west_south_east_north",
        "pixel_size",
        "source_service",
        "source_attribution",
        "source_endpoint",
        "sha256",
        "bytes",
        "status",
        "downloaded_utc",
        "note",
    ]
    with path.open("w", newline="", encoding="utf-8") as file_handle:
        writer = csv.DictWriter(file_handle, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=Path("data/test-images"),
        help="Directory containing the ignored images and tracked manifest (default: data/test-images).",
    )
    parser.add_argument("--max-items", type=int, default=None, help="Limit the plan for a quick smoke run.")
    parser.add_argument("--dry-run", action="store_true", help="Write a planned manifest without downloading images.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    image_plan = planned_images()
    if args.max_items is not None:
        if args.max_items < 1:
            raise SystemExit("--max-items must be at least 1.")
        image_plan = image_plan[: args.max_items]

    args.output_root.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, str | int]] = []
    counts = {"downloaded": 0, "cached": 0, "failed": 0, "planned": 0}

    for position, image in enumerate(image_plan, start=1):
        destination = args.output_root / image.relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        if args.dry_run:
            status, note = "planned", "dry run; no request made"
        elif is_valid_jpeg(destination):
            status, note = "cached", "existing JPEG retained"
        else:
            status, note = download_image(request_url(image), destination)
        counts[status] += 1
        rows.append(manifest_row(image, destination, status, note))
        if status == "failed":
            print(f"[{position}/{len(image_plan)}] failed: {image.relative_path} ({note})", file=sys.stderr)

    write_manifest(args.output_root / "manifest.csv", rows)
    print(
        "USGS imagery pack: "
        + ", ".join(f"{name}={counts[name]}" for name in ("downloaded", "cached", "failed", "planned"))
        + f", manifest={args.output_root / 'manifest.csv'}"
    )
    return 1 if counts["failed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
