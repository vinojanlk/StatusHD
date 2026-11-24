package com.techwin.statushd

enum class SelectionValidationReason {
    EMPTY,
    TOO_FEW,
    TOO_MANY,
    VALID
}

data class SelectionPolicyResult(
    val acceptedIdentifiers: List<String>,
    val duplicatesRemoved: Int,
    val isValidForStart: Boolean,
    val reasonCode: SelectionValidationReason
)

object BatchPhotoSelectionPolicy {
    fun validateAndDeduplicate(selectedIdentifiers: List<String>): SelectionPolicyResult {
        val distinctList = selectedIdentifiers.distinct()
        val duplicatesRemoved = selectedIdentifiers.size - distinctList.size
        val count = distinctList.size

        return when {
            count == 0 -> SelectionPolicyResult(
                acceptedIdentifiers = emptyList(),
                duplicatesRemoved = duplicatesRemoved,
                isValidForStart = false,
                reasonCode = SelectionValidationReason.EMPTY
            )
            count == 1 -> SelectionPolicyResult(
                acceptedIdentifiers = distinctList,
                duplicatesRemoved = duplicatesRemoved,
                isValidForStart = false,
                reasonCode = SelectionValidationReason.TOO_FEW
            )
            count in 2..10 -> SelectionPolicyResult(
                acceptedIdentifiers = distinctList,
                duplicatesRemoved = duplicatesRemoved,
                isValidForStart = true,
                reasonCode = SelectionValidationReason.VALID
            )
            else -> SelectionPolicyResult(
                acceptedIdentifiers = emptyList(),
                duplicatesRemoved = duplicatesRemoved,
                isValidForStart = false,
                reasonCode = SelectionValidationReason.TOO_MANY
            )
        }
    }
}
