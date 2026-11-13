package tn.malak.app.ui

/**
 * Disclosure boundary between capture and authenticated server OCR.
 * Pilot sign-in is only a draft demo, never proof of identity.
 */
internal object FrontReviewPolicy {
    fun showFields(trustedSession:Boolean,consent:Boolean,reading:Boolean,
                   replyReceived:Boolean,replyValid:Boolean):Boolean =
        trustedSession&&consent&&!reading&&replyReceived&&replyValid
}
