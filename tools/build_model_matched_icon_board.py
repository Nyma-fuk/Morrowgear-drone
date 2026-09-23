from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "docs" / "design" / "item-icons-model-matched-proposal"
RENDERS = DESIGN / "renders"
CURRENT = ROOT / "src" / "main" / "resources" / "assets" / "morrowgear_drone" / "textures" / "item"

ITEMS = [
    ("AIRFRAME", "FIELD UNIT", "field.png", "MODEL"),
    ("ROLE MODULES", "SCOUT", "scout_module.png", "MODEL"),
    ("ROLE MODULES", "CARGO", "cargo_module.png", "MODEL"),
    ("ROLE MODULES", "ENGINEER", "engineer_module.png", "MODEL"),
    ("ROLE MODULES", "SECURITY", "security_module.png", "MODEL"),
    ("ROLE MODULES", "SALVAGE", "salvage_module.png", "SYSTEM"),
    ("POWER", "BATTERY S", "standard_battery_pack.png", "SYSTEM"),
    ("POWER", "BATTERY M", "reinforced_battery_pack.png", "SYSTEM"),
    ("POWER", "BATTERY L", "high_density_battery_pack.png", "SYSTEM"),
    ("POWER", "POWER CELL", "power_cell.png", "SYSTEM"),
    ("COMPONENTS", "ACTUATOR", "flight_actuator.png", "SYSTEM"),
    ("COMPONENTS", "RAW COMPOSITE", "raw_morrow_composite.png", "SYSTEM"),
    ("COMPONENTS", "MORROW ALLOY", "morrow_alloy.png", "SYSTEM"),
    ("COMPONENTS", "CONTROL BOARD", "basic_control_board.png", "SYSTEM"),
    ("COMPONENTS", "LIGHT FRAME", "lightweight_frame.png", "SYSTEM"),
    ("INFRASTRUCTURE", "DOCK", "dock_item.png", "MODEL"),
    ("INFRASTRUCTURE", "SOLAR STATION", "solar_station.png", "MODEL"),
    ("CONTROL", "CONTROLLER", "controller.png", "MODEL"),
    ("CONTROL", "TACTICAL VISOR", "tactical_visor.png", "SYSTEM"),
    ("TOOLS", "RECOVERY TOOL", "recovery_tool.png", "SYSTEM"),
    ("WEAPONS", "AUTOCANNON", "autocannon_module.png", "SYSTEM"),
    ("WEAPONS", "LASER", "laser_module.png", "SYSTEM"),
    ("WEAPONS", "MISSILE", "missile_module.png", "SYSTEM"),
]


def font(size, bold=False):
    name = "seguisb.ttf" if bold else "segoeui.ttf"
    return ImageFont.truetype(str(Path("C:/Windows/Fonts") / name), size)


def source_image(filename, source_type):
    if source_type == "MODEL" and (RENDERS / filename).exists():
        return Image.open(RENDERS / filename).convert("RGBA")
    return Image.open(CURRENT / filename).convert("RGBA")


def fit_icon(image, size=82):
    alpha = image.getchannel("A")
    bbox = alpha.getbbox()
    if bbox:
        image = image.crop(bbox)
    image.thumbnail((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(image, ((size - image.width) // 2, (size - image.height) // 2))
    return canvas


def main():
    DESIGN.mkdir(parents=True, exist_ok=True)
    width, height = 1920, 1260
    board = Image.new("RGB", (width, height), "#0b1014")
    draw = ImageDraw.Draw(board)

    draw.text((62, 42), "MORROWGEAR", font=font(30, True), fill="#f3f7f8")
    draw.text((306, 46), "MODEL-MATCHED ITEM ICON PROPOSAL", font=font(24), fill="#45dbe5")
    draw.text((62, 92), "Canonical geometry first / fixed 3-4 camera / 64 px inventory target", font=font(17), fill="#8fa5ad")

    card_w, card_h = 204, 196
    gap_x, gap_y = 18, 24
    start_x, start_y = 62, 150
    cols = 8

    for index, (category, label, filename, source_type) in enumerate(ITEMS):
        row, col = divmod(index, cols)
        x = start_x + col * (card_w + gap_x)
        y = start_y + row * (card_h + gap_y)
        fill = "#121b21"
        border = "#27414a"
        draw.rounded_rectangle((x, y, x + card_w, y + card_h), radius=6, fill=fill, outline=border, width=2)
        draw.rectangle((x, y, x + 5, y + card_h), fill="#25d7e3" if source_type == "MODEL" else "#d68a25")

        image = fit_icon(source_image(filename, source_type), 94)
        board.paste(image, (x + (card_w - 94) // 2, y + 22), image)

        tag_color = "#4de9f2" if source_type == "MODEL" else "#e4a64b"
        draw.text((x + 16, y + 126), category, font=font(12, True), fill="#718a93")
        draw.text((x + 16, y + 146), label, font=font(16, True), fill="#edf5f6")
        draw.text((x + 16, y + 174), "MODEL-DERIVED" if source_type == "MODEL" else "SYSTEM-DERIVED", font=font(11, True), fill=tag_color)

    legend_y = 1056
    draw.line((62, legend_y, 1858, legend_y), fill="#28414a", width=2)
    draw.text((62, legend_y + 26), "GEOMETRY CONTRACT", font=font(18, True), fill="#eaf2f3")
    draw.text((62, legend_y + 60), "MODEL-DERIVED: silhouette and component placement come directly from the approved 3D model.", font=font(16), fill="#8fabb3")
    draw.text((62, legend_y + 89), "SYSTEM-DERIVED: no mounted/world model exists; shared bevel, graphite shell, cyan status light and orange service latch apply.", font=font(16), fill="#8fabb3")
    draw.text((62, legend_y + 118), "Final target: 64 x 64, 70-78% cell occupancy, cyan reserved for power/status, orange reserved for service/munition interfaces.", font=font(16), fill="#8fabb3")

    board.save(DESIGN / "morrowgear-model-matched-icon-proposal.png", optimize=True)


if __name__ == "__main__":
    main()
