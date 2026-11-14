import Foundation
import Vision
import AppKit
import PDFKit
import ImageIO

// Private bounded worker: JSON only on stdout, no source text in stderr.
func scan(_ cg: CGImage, _ page: Int) throws -> [String: Any] {
    guard cg.width >= 175, cg.height >= 175, cg.width * cg.height <= 25_000_000 else { throw NSError(domain:"dimensions",code:1) }
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.recognitionLanguages = ["ar-SA", "fr-FR", "en-US"]
    request.usesLanguageCorrection = false
    try VNImageRequestHandler(cgImage: cg, options: [:]).perform([request])
    let observations = request.results ?? []
    guard observations.count <= 600 else { throw NSError(domain:"lines",code:1) }
    var lines: [[String:Any]] = []
    var words: [[String:Any]] = []
    func box(_ r: CGRect) -> [String:Int] {
        let l=max(0,Int(r.minX * Double(cg.width)))
        let t=max(0,Int((1-r.maxY) * Double(cg.height)))
        return ["left":l,"top":t,"right":min(cg.width,max(l+1,Int(ceil(r.maxX*Double(cg.width))))),"bottom":min(cg.height,max(t+1,Int(ceil((1-r.minY)*Double(cg.height)))))]
    }
    for ob in observations {
        guard let candidate=ob.topCandidates(1).first else { continue }
        let text=candidate.string
        guard text.count <= 1200 else { throw NSError(domain:"line",code:1) }
        let b=box(ob.boundingBox)
        lines.append(["text":text,"page":page,"confidence":Double(candidate.confidence),"source":"ocr-ar","bbox":b])
        text.enumerateSubstrings(in:text.startIndex..<text.endIndex, options:.byWords) { value,range,_,_ in
            if let value=value, value.count <= 120, let rect=try? candidate.boundingBox(for:range) {
                words.append(["text":value,"page":page,"confidence":Double(candidate.confidence),"bbox":box(rect.boundingBox)])
            }
        }
    }
    var result:[String:Any] = ["page":page,"method":"ocr","lines":lines]
    if cg.width >= 200 && cg.height >= 300 {
        result["pageImageWidth"]=cg.width; result["pageImageHeight"]=cg.height
        result["ocrWords"]=Array(words.prefix(1000)); result["spatialWordsTruncated"]=words.count>1000
    }
    return result
}
do {
    guard CommandLine.arguments.count == 2 else { throw NSError(domain:"args",code:1) }
    let url=URL(fileURLWithPath:CommandLine.arguments[1])
    let data=try Data(contentsOf:url, options:.mappedIfSafe)
    guard data.count >= 20 && data.count <= 12*1024*1024 else { throw NSError(domain:"size",code:1) }
    var pages:[[String:Any]]=[]
    var format="image"
    if data.starts(with:[0x25,0x50,0x44,0x46,0x2d]) {
        format="pdf"
        guard let pdf=PDFDocument(data:data), !pdf.isLocked, pdf.pageCount>0, pdf.pageCount<=8 else { throw NSError(domain:"pdf",code:1) }
        for n in 0..<pdf.pageCount {
            guard let page=pdf.page(at:n) else { throw NSError(domain:"page",code:1) }
            let bounds=page.bounds(for:.mediaBox)
            guard bounds.width>0,bounds.height>0,bounds.width<=16000,bounds.height<=16000 else { throw NSError(domain:"page",code:1) }
            let scale=min(2.5,2400/max(bounds.width,bounds.height))
            let size=NSSize(width:max(200,bounds.width*scale),height:max(300,bounds.height*scale))
            let image=page.thumbnail(of:size,for:.mediaBox)
            guard let cg=image.cgImage(forProposedRect:nil,context:nil,hints:nil) else { throw NSError(domain:"raster",code:1) }
            pages.append(try scan(cg,n+1))
        }
    } else {
        guard let source=CGImageSourceCreateWithData(data as CFData,nil),
              CGImageSourceGetCount(source)==1,
              let props=CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [CFString:Any],
              let width=props[kCGImagePropertyPixelWidth] as? Int,
              let height=props[kCGImagePropertyPixelHeight] as? Int,
              width>=175,height>=175,width<=10000,height<=10000,width*height<=25_000_000 else { throw NSError(domain:"image",code:1) }
        let options:[CFString:Any]=[kCGImageSourceCreateThumbnailFromImageAlways:true,
          kCGImageSourceCreateThumbnailWithTransform:true,kCGImageSourceThumbnailMaxPixelSize:3000]
        guard let cg=CGImageSourceCreateThumbnailAtIndex(source,0,options as CFDictionary) else { throw NSError(domain:"image",code:1) }
        pages.append(try scan(cg,1))
    }
    let output:[String:Any] = ["format":format,"pages":pages,"mayApproveOwnership":false]
    let result=try JSONSerialization.data(withJSONObject:output)
    guard result.count<=4_000_000 else { throw NSError(domain:"output",code:1) }
    FileHandle.standardOutput.write(result)
} catch { FileHandle.standardError.write(Data("DOCUMENT_PROCESSING_FAILED\n".utf8)); exit(1) }
