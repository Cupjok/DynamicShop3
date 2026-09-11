package me.sat7.dynamicshop.transactions;

/**
 * Whether a MultiCurrency withdraw/deposit was applied, derived only from its TransactionResult.
 */
public enum MultiCurrencyOutcome
{
    APPLIED,
    NOT_APPLIED,
    UNKNOWN;

    /**
     * @param success              TransactionResult.success()
     * @param failureReason        FailureReason name, "EXCEPTION" when the call itself failed, or null
     * @param mayAlreadyBeApplied  true when an earlier attempt with the same idempotency key may have committed
     *                             (a previous OUTCOME_UNKNOWN, a lost future, or an order recovered after a restart)
     */
    public static MultiCurrencyOutcome Classify(boolean success, String failureReason, boolean mayAlreadyBeApplied)
    {
        if (success)
            return APPLIED;
        if (failureReason == null)
            return UNKNOWN;

        switch (failureReason)
        {
            // The key was already committed, i.e. an earlier attempt of this very order went through.
            // (Keys are unique per order - a UUID - so it cannot belong to anything else.)
            case "DUPLICATE_TRANSACTION":
                return APPLIED;

            // Commit failed / the call never answered: must be resolved by retrying with the same key.
            case "OUTCOME_UNKNOWN":
            case "EXCEPTION":
                return UNKNOWN;

            // Rejected inside the database transaction, after MultiCurrency looked the idempotency key up and did
            // not find it - so this key was never committed. Holds for retries too.
            case "INSUFFICIENT_FUNDS":
            case "BALANCE_LIMIT_EXCEEDED":
                return NOT_APPLIED;

            // Every other failure means "this request was not applied" (API contract). On a first attempt that
            // settles it. After a possible earlier commit it only proves the retry did nothing: the earlier attempt
            // may still have committed (e.g. the currency was disabled in between), so keep it unresolved.
            default:
                return mayAlreadyBeApplied ? UNKNOWN : NOT_APPLIED;
        }
    }
}
