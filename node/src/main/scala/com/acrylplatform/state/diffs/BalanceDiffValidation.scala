package com.acrylplatform.state.diffs

import cats.implicits._
import com.acrylplatform.account.Address
import com.acrylplatform.common.state.ByteStr
import com.acrylplatform.settings.FunctionalitySettings
import com.acrylplatform.state.{Blockchain, Diff, Portfolio}
import com.acrylplatform.transaction.Asset.Acryl
import com.acrylplatform.transaction.TxValidationError.AccountBalanceError
import com.acrylplatform.utils.ScorexLogging

import scala.util.{Left, Right}

object BalanceDiffValidation extends ScorexLogging {

  def apply(b: Blockchain, currentHeight: Int, fs: FunctionalitySettings)(d: Diff): Either[AccountBalanceError, Diff] = {
    val changedAccounts = d.portfolios.keySet

    def check(acc: Address): Option[(Address, String)] = {
      val portfolioDiff = d.portfolios(acc)

      val balance       = portfolioDiff.balance
      lazy val oldAcryl = b.balance(acc, Acryl)
      lazy val oldLease = b.leaseBalance(acc)
      lazy val lease    = cats.Monoid.combine(oldLease, portfolioDiff.lease)
      (if (balance < 0) {
         val newB = oldAcryl + balance

         if (newB < 0) {
           Some(acc -> s"negative acryl balance: $acc, old: $oldAcryl, new: $newB")
         } else if (newB < lease.out && currentHeight > fs.allowLeasedBalanceTransferUntilHeight) {
           Some(acc -> (if (newB + lease.in - lease.out < 0) {
                          s"negative effective balance: $acc, old: ${(oldAcryl, oldLease)}, new: ${(newB, lease)}"
                        } else if (portfolioDiff.lease.out == 0) {
                          s"$acc trying to spend leased money"
                        } else {
                          s"leased being more than own: $acc, old: ${(oldAcryl, oldLease)}, new: ${(newB, lease)}"
                        }))
         } else {
           None
         }
       } else {
         None
       }) orElse (portfolioDiff.assets find {
        case (a, c) =>
          // Tokens it can produce overflow are exist.
          val oldB = b.balance(acc, a)
          val newB = oldB + c
          newB < 0
      } map { _ =>
        acc -> s"negative asset balance: $acc, new portfolio: ${negativeAssetsInfo(b.portfolio(acc).combine(portfolioDiff))}"
      })
    }

    val positiveBalanceErrors: Map[Address, String] = changedAccounts.flatMap(check).toMap

    if (positiveBalanceErrors.isEmpty) {
      Right(d)
    } else {
      Left(AccountBalanceError(positiveBalanceErrors))
    }
  }

  private def negativeAssetsInfo(p: Portfolio): Map[ByteStr, Long] =
    p.assets.collect {
      case (asset, balance) if balance < 0 => (asset.id, balance)
    }
}
