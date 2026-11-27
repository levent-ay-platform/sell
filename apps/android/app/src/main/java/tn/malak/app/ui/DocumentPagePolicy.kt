package tn.malak.app.ui

internal fun <T> removeDocumentPage(pages:List<T>,index:Int):List<T> {
    require(index in pages.indices)
    return pages.filterIndexed{i,_->i!=index}
}
internal fun <T> replaceDocumentPage(pages:List<T>,index:Int,replacement:T):List<T> {
    require(index in pages.indices)
    return pages.toMutableList().also{it[index]=replacement}
}
internal fun <T> moveDocumentPage(pages:List<T>,from:Int,to:Int):List<T> {
    require(from in pages.indices&&to in pages.indices)
    return pages.toMutableList().also{it.add(to,it.removeAt(from))}
}
