// Renders the demo video's captions and title cards as 2560x1440 PNGs (transparent for captions).
//
//   swift tools/demo/captions.swift spec.json
//
// spec.json: [{"file": "out.png", "kind": "lower" | "card", "title": "...", "sub": "...",
//              "foot": "...", "icon": "icon.png", "tag": "..."}]
// "lower": a caption in the left panel (over the car's status panel, never over the app), optional
// small "tag" above the title. "card": a full-screen title card with the app icon. macOS fonts only (Apple SD Gothic Neo).
import AppKit
import Foundation

let W: CGFloat = 2560, H: CGFloat = 1440
let mint = NSColor(calibratedRed: 0.37, green: 0.90, blue: 0.72, alpha: 1)

struct Item: Decodable {
    let file: String
    let kind: String
    let title: String
    let sub: String?
    let foot: String?
    let icon: String?
    let tag: String?
}

func font(_ size: CGFloat, _ weight: String) -> NSFont {
    NSFont(name: "AppleSDGothicNeo-\(weight)", size: size) ?? NSFont.systemFont(ofSize: size)
}

var align: NSTextAlignment = .center

func attr(_ s: String, _ f: NSFont, _ c: NSColor, kern: CGFloat = 0) -> NSAttributedString {
    let p = NSMutableParagraphStyle()
    p.alignment = align
    p.lineSpacing = 6
    return NSAttributedString(string: s, attributes: [.font: f, .foregroundColor: c, .paragraphStyle: p, .kern: kern])
}

func render(_ item: Item) throws {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(W), pixelsHigh: Int(H), bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let ctx = NSGraphicsContext.current!.cgContext
    ctx.clear(CGRect(x: 0, y: 0, width: W, height: H))

    align = item.kind == "card" ? .center : .left
    if item.kind == "card" {
        // Deep night-blue to the app's dark teal, like the fog map at night.
        let g = NSGradient(colors: [NSColor(calibratedRed: 0.04, green: 0.06, blue: 0.10, alpha: 1),
                                    NSColor(calibratedRed: 0.05, green: 0.13, blue: 0.14, alpha: 1)])!
        g.draw(in: CGRect(x: 0, y: 0, width: W, height: H), angle: 90)
        var y: CGFloat = H / 2 + 120
        if let icon = item.icon, let img = NSImage(contentsOfFile: icon) {
            let s: CGFloat = 240
            let r = CGRect(x: (W - s) / 2, y: y + 40, width: s, height: s)
            NSBezierPath(roundedRect: r, xRadius: 56, yRadius: 56).addClip()
            img.draw(in: r)
            ctx.resetClip()
        } else {
            y += 60
        }
        let t = attr(item.title, font(120, "Bold"), .white, kern: -1)
        let ts = t.size()
        t.draw(in: CGRect(x: 0, y: y - ts.height, width: W, height: ts.height + 10))
        y -= ts.height + 30
        if let sub = item.sub {
            let a = attr(sub, font(54, "Medium"), mint)
            let s = a.boundingRect(with: CGSize(width: W - 400, height: 400), options: [.usesLineFragmentOrigin])
            a.draw(with: CGRect(x: 200, y: y - s.height, width: W - 400, height: s.height), options: [.usesLineFragmentOrigin])
            y -= s.height + 50
        }
        if let foot = item.foot {
            let a = attr(foot, font(38, "Regular"), NSColor(white: 0.72, alpha: 1))
            let s = a.boundingRect(with: CGSize(width: W - 400, height: 400), options: [.usesLineFragmentOrigin])
            a.draw(with: CGRect(x: 200, y: y - s.height, width: W - 400, height: s.height), options: [.usesLineFragmentOrigin])
        }
    } else {
        // Side panel: over the left third of the Pleos screen (the car's own status panel), which the
        // app never uses, so a caption never hides what the app is showing.
        let panelX: CGFloat = 36, panelW: CGFloat = 790
        let maxW = panelW - 96
        let title = attr(item.title, font(52, "Bold"), .white)
        let sub = item.sub.map { attr($0, font(36, "Medium"), NSColor(white: 0.86, alpha: 1)) }
        let tag = item.tag.map { attr($0, font(30, "Bold"), mint, kern: 1) }
        let tb = title.boundingRect(with: CGSize(width: maxW, height: 900), options: [.usesLineFragmentOrigin])
        let sb = sub?.boundingRect(with: CGSize(width: maxW, height: 900), options: [.usesLineFragmentOrigin]) ?? .zero
        let gb = tag?.boundingRect(with: CGSize(width: maxW, height: 200), options: [.usesLineFragmentOrigin]) ?? .zero
        let padY: CGFloat = 40, gap: CGFloat = 16
        let h = tb.height + (sub != nil ? sb.height + gap : 0) + (tag != nil ? gb.height + gap : 0) + padY * 2
        // Centred on the car picture, between the status row and the media widget.
        let midY: CGFloat = H - 700
        let box = CGRect(x: panelX, y: midY - h / 2, width: panelW, height: h)
        NSColor(calibratedRed: 0.03, green: 0.05, blue: 0.08, alpha: 0.88).setFill()
        NSBezierPath(roundedRect: box, xRadius: 32, yRadius: 32).fill()
        mint.setFill()
        NSBezierPath(roundedRect: CGRect(x: box.minX, y: box.minY + 32, width: 8, height: h - 64), xRadius: 4, yRadius: 4).fill()
        var y = box.maxY - padY
        let x = box.minX + 48
        if let tag {
            tag.draw(with: CGRect(x: x, y: y - gb.height, width: maxW, height: gb.height), options: [.usesLineFragmentOrigin])
            y -= gb.height + gap
        }
        title.draw(with: CGRect(x: x, y: y - tb.height, width: maxW, height: tb.height), options: [.usesLineFragmentOrigin])
        y -= tb.height + gap
        if let sub {
            sub.draw(with: CGRect(x: x, y: y - sb.height, width: maxW, height: sb.height), options: [.usesLineFragmentOrigin])
        }
    }
    NSGraphicsContext.restoreGraphicsState()
    guard let png = rep.representation(using: .png, properties: [:]) else { throw NSError(domain: "png", code: 1) }
    try png.write(to: URL(fileURLWithPath: item.file))
}

let spec = try JSONDecoder().decode([Item].self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
for item in spec { try render(item) }
print("\(spec.count) images")
