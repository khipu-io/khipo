package khipu.consensus.ibft.statemachine

import khipu.consensus.ibft.ConsensusRoundIdentifier
import khipu.consensus.ibft.ibftevent.RoundExpiry
import khipu.consensus.ibft.messagewrappers.Commit
import khipu.consensus.ibft.messagewrappers.Prepare
import khipu.consensus.ibft.messagewrappers.Proposal
import khipu.consensus.ibft.messagewrappers.RoundChange
import khipu.domain.BlockHeader

trait BlockHeightManager {

  def handleBlockTimerExpiry(roundIdentifier: ConsensusRoundIdentifier);

  def roundExpired(expire: RoundExpiry);

  def handleProposalPayload(proposal: Proposal);

  def handlePreparePayload(prepare: Prepare);

  def handleCommitPayload(commit: Commit);

  def handleRoundChangePayload(roundChange: RoundChange);

  def getChainHeight(): Long

  def getParentBlockHeader(): BlockHeader
}