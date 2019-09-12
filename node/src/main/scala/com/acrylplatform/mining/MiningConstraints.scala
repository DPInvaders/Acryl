package com.acrylplatform.mining

import cats.data.NonEmptyList
import com.acrylplatform.block.Block
import com.acrylplatform.features.BlockchainFeatures
import com.acrylplatform.features.FeatureProvider._
import com.acrylplatform.settings.MinerSettings
import com.acrylplatform.state.Blockchain

case class MiningConstraints(total: MiningConstraint, keyBlock: MiningConstraint, micro: MiningConstraint)

object MiningConstraints {
  val MaxScriptRunsInBlock = 100
  val MaxScriptsComplexityInBlock = 1000000
  val ClassicAmountOfTxsInBlock = 100
  val MaxTxsSizeInBytes = 1 * 1024 * 1024 // 1 megabyte

  def apply(blockchain: Blockchain, height: Int, minerSettings: Option[MinerSettings] = None): MiningConstraints = {
    val activatedFeatures     = blockchain.activatedFeaturesAt(height)
    val isNgEnabled           = activatedFeatures.contains(BlockchainFeatures.NG.id)
    val isMassTransferEnabled = activatedFeatures.contains(BlockchainFeatures.MassTransfer.id)
    val isScriptEnabled       = activatedFeatures.contains(BlockchainFeatures.SmartAccounts.id)
    val isDAppsEnabled        = activatedFeatures.contains(BlockchainFeatures.Ride4DApps.id)

    val total: MiningConstraint =
      if (isMassTransferEnabled) OneDimensionalMiningConstraint(MaxTxsSizeInBytes, TxEstimators.sizeInBytes, "MaxTxsSizeInBytes")
      else {
        val maxTxs = if (isNgEnabled) Block.MaxTransactionsPerBlockVer3 else ClassicAmountOfTxsInBlock
        OneDimensionalMiningConstraint(maxTxs, TxEstimators.one, "MaxTxs")
      }

    new MiningConstraints(
      total =
        if (isDAppsEnabled)
          MultiDimensionalMiningConstraint(
            NonEmptyList
              .of(OneDimensionalMiningConstraint(MaxScriptsComplexityInBlock, TxEstimators.scriptsComplexity, "MaxScriptsComplexityInBlock"), total))
        else if (isScriptEnabled)
          MultiDimensionalMiningConstraint(
            NonEmptyList.of(OneDimensionalMiningConstraint(MaxScriptRunsInBlock, TxEstimators.scriptRunNumber, "MaxScriptRunsInBlock"), total))
        else total,
      keyBlock =
        if (isNgEnabled)
          if (isMassTransferEnabled)
            OneDimensionalMiningConstraint(0, TxEstimators.one, "MaxTxsInKeyBlock")
          else
            minerSettings
              .map(ms => OneDimensionalMiningConstraint(ms.maxTransactionsInKeyBlock, TxEstimators.one, "MaxTxsInKeyBlock"))
              .getOrElse(MiningConstraint.Unlimited)
        else OneDimensionalMiningConstraint(ClassicAmountOfTxsInBlock, TxEstimators.one, "MaxTxsInKeyBlock"),
      micro =
        if (isNgEnabled && minerSettings.isDefined)
          OneDimensionalMiningConstraint(minerSettings.get.maxTransactionsInMicroBlock, TxEstimators.one, "MaxTxsInMicroBlock")
        else MiningConstraint.Unlimited
    )
  }
}