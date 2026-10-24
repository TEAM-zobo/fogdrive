"""Builds the FogDrive map styles (day/night) from OpenFreeMap Positron.

    python3 tools/mapstyle/build_styles.py

Positron is used for its layer structure and the OpenFreeMap tile/glyph/sprite sources only.
Colours are replaced with the FogDrive palette, labels prefer Korean (`name:ko`), and road widths
are bumped so the network reads at a glance. Output: app/src/main/assets/map/fogdrive-{day,night}.json
"""
import copy
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "map")

PALETTES = {
    "day": {
        "background": "#F4F1EA", "residential": "#EFEBE3", "park": "#D3E8C6", "wood": "#C6DEB8",
        "ice": "#F8F8F6", "water": "#9ECFEF", "waterway": "#8EC4E8",
        "building": "#E4DED3", "building_outline": "#D6CFC2",
        "path": "#E6DFD2", "minor": "#FFFFFF", "major_casing": "#E2D6C0", "major": "#FFFFFF",
        "subtle": "#E9E3D8", "motorway_casing": "#E3A246", "motorway": "#FFD27F",
        "tunnel_casing": "#E6DCCB", "tunnel": "#F7F1E6", "rail": "#CFC7B9", "rail_dash": "#F4F1EA",
        "aeroway": "#ECE7DD", "boundary": "#B9AE9E",
        "place_text": "#2E3540", "place_halo": "rgba(244,241,234,0.92)",
        "road_text": "#5B6573", "road_halo": "#FFFFFF", "water_text": "#3B6C9C",
    },
    "night": {
        "background": "#1B2230", "residential": "#1F2735", "park": "#1D3A30", "wood": "#1B372C",
        "ice": "#232B38", "water": "#0F2F49", "waterway": "#1A4466",
        "building": "#283244", "building_outline": "#2F3A4D",
        "path": "#2D3747", "minor": "#3C485B", "major_casing": "#1B2230", "major": "#7E8BA0",
        "subtle": "#323D4F", "motorway_casing": "#7A5420", "motorway": "#E8A64A",
        "tunnel_casing": "#2A3445", "tunnel": "#364254", "rail": "#3A4658", "rail_dash": "#1B2230",
        "aeroway": "#262F3E", "boundary": "#4C586B",
        "place_text": "#E6EBF2", "place_halo": "rgba(18,24,34,0.9)",
        "road_text": "#AEB8C6", "road_halo": "#121822", "water_text": "#7FB2FF",
    },
}

# layer id -> {paint property: palette key}
COLOURS = {
    "background": {"background-color": "background"},
    "park": {"fill-color": "park"},
    "water": {"fill-color": "water"},
    "landcover_ice_shelf": {"fill-color": "ice"},
    "landcover_glacier": {"fill-color": "ice"},
    "landuse_residential": {"fill-color": "residential"},
    "landcover_wood": {"fill-color": "wood"},
    "waterway": {"line-color": "waterway"},
    "building": {"fill-color": "building", "fill-outline-color": "building_outline"},
    "tunnel_motorway_casing": {"line-color": "tunnel_casing"},
    "tunnel_motorway_inner": {"line-color": "tunnel"},
    "aeroway-taxiway": {"line-color": "aeroway"},
    "aeroway-runway-casing": {"line-color": "aeroway"},
    "aeroway-area": {"fill-color": "aeroway"},
    "aeroway-runway": {"line-color": "aeroway"},
    "road_area_pier": {"fill-color": "background"},
    "road_pier": {"line-color": "background"},
    "highway_path": {"line-color": "path"},
    "highway_minor": {"line-color": "minor"},
    "highway_major_casing": {"line-color": "major_casing"},
    "highway_major_inner": {"line-color": "major"},
    "highway_major_subtle": {"line-color": "subtle"},
    "highway_motorway_casing": {"line-color": "motorway_casing"},
    "highway_motorway_inner": {"line-color": "motorway"},
    "highway_motorway_subtle": {"line-color": "subtle"},
    "railway_transit": {"line-color": "rail"},
    "railway_transit_dashline": {"line-color": "rail_dash"},
    "railway_service": {"line-color": "rail"},
    "railway_service_dashline": {"line-color": "rail_dash"},
    "railway": {"line-color": "rail"},
    "railway_dashline": {"line-color": "rail_dash"},
    "highway_motorway_bridge_casing": {"line-color": "motorway_casing"},
    "highway_motorway_bridge_inner": {"line-color": "motorway"},
    "boundary_3": {"line-color": "boundary"},
    "boundary_2": {"line-color": "boundary"},
    "boundary_disputed": {"line-color": "boundary"},
}
ROAD_LABELS = {"highway-name-path", "highway-name-minor", "highway-name-major"}
WATER_LABELS = {"waterway_line_label", "water_name_point_label", "water_name_line_label"}
# Wider roads read better on a car display at arm's length.
WIDEN = {"highway_minor": 1.4, "highway_major_casing": 1.25, "highway_major_inner": 1.25,
         "highway_motorway_casing": 1.2, "highway_motorway_inner": 1.2}
KOREAN = ["coalesce", ["get", "name:ko"], ["get", "name"]]
# Positron's 9-14 px labels are phone-sized; the head unit is 160 dpi viewed from arm's length,
# where they turned soft and hard to read under the fog.
TEXT_SCALE = 1.4


def scale_width(value, factor):
    """Scales numbers inside a width/size value (plain number, stops object or expression)."""
    if isinstance(value, (int, float)):
        return round(value * factor, 2)
    if isinstance(value, dict) and "stops" in value:
        return {**value, "stops": [[z, scale_width(w, factor)] for z, w in value["stops"]]}
    if isinstance(value, list) and value and value[0] == "interpolate":
        head, rest = value[:3], value[3:]
        return head + [v if i % 2 == 0 else scale_width(v, factor) for i, v in enumerate(rest)]
    return value


def build(base, theme):
    pal = PALETTES[theme]
    style = copy.deepcopy(base)
    style["name"] = f"FogDrive {theme}"
    for layer in style["layers"]:
        lid = layer["id"]
        paint = layer.setdefault("paint", {})
        for prop, key in COLOURS.get(lid, {}).items():
            paint[prop] = pal[key]
        if lid in WIDEN and "line-width" in paint:
            paint["line-width"] = scale_width(paint["line-width"], WIDEN[lid])
        if layer["type"] == "symbol" and "text-field" in layer.get("layout", {}):
            layer["layout"]["text-field"] = KOREAN
            if "text-size" in layer["layout"]:
                layer["layout"]["text-size"] = scale_width(layer["layout"]["text-size"], TEXT_SCALE)
            # Two font stacks instead of three: Italic (water names) uses Regular, so one less set
            # of glyph ranges is downloaded and kept in the glyph atlas.
            if layer["layout"].get("text-font") == ["Noto Sans Italic"]:
                layer["layout"]["text-font"] = ["Noto Sans Regular"]
            if lid in ROAD_LABELS:
                paint.update({"text-color": pal["road_text"], "text-halo-color": pal["road_halo"], "text-halo-width": 1.4})
            elif lid in WATER_LABELS:
                paint.update({"text-color": pal["water_text"], "text-halo-color": pal["place_halo"], "text-halo-width": 1.2})
            else:
                paint.update({"text-color": pal["place_text"], "text-halo-color": pal["place_halo"], "text-halo-width": 1.6})
    return style


def main():
    with open(os.path.join(HERE, "positron.json"), encoding="utf-8") as f:
        base = json.load(f)
    os.makedirs(OUT, exist_ok=True)
    for theme in PALETTES:
        path = os.path.join(OUT, f"fogdrive-{theme}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(build(base, theme), f, ensure_ascii=False, separators=(",", ":"))
        print(os.path.relpath(path, os.path.join(HERE, "..", "..")))


if __name__ == "__main__":
    main()
