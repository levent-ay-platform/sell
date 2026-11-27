import Foundation
import AppKit
import PDFKit
import ImageIO
let args = CommandLine.arguments
guard args.count == 3,
      let doc = PDFDocument(url: URL(fileURLWithPath: args[1])),
      !doc.isLocked, doc.pageCount > 0, doc.pageCount <= 8,
      let page = doc.page(at: 0) else { exit(2) }
let bounds = page.bounds(for: .mediaBox)
guard bounds.width > 0, bounds.height > 0,
      bounds.width <= 16000, bounds.height <= 16000 else { exit(3) }
let scale = 2400 / max(bounds.width, bounds.height)
let image = page.thumbnail(of: NSSize(width: bounds.width * scale,
    height: bounds.height * scale), for: .mediaBox)
guard let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil),
      let sink = CGImageDestinationCreateWithURL(URL(fileURLWithPath: args[2]) as CFURL,
          "public.png" as CFString, 1, nil) else { exit(4) }
CGImageDestinationAddImage(sink, cg, nil)
guard CGImageDestinationFinalize(sink) else { exit(5) }
