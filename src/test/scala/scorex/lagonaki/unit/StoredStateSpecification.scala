package scorex.lagonaki.unit

import java.io.File
import scala.util.Random
import org.h2.mvstore.MVStore
import org.scalatest.{FunSuite, Matchers}
import scorex.account.{Account, PrivateKeyAccount}
import scorex.crypto.encode.Base58
import scorex.lagonaki.mocks.BlockMock
import scorex.transaction.{AssetAcc, GenesisTransaction}
import scorex.transaction.assets.{IssueTransaction, TransferTransaction}
import scorex.transaction.state.database.blockchain.StoredState
import scorex.transaction.state.wallet.{IssueRequest, TransferRequest}
import scorex.wallet.Wallet

class StoredStateSpecification extends FunSuite with Matchers {


  val folder = "/tmp/scorex/test/"
  new File(folder).mkdirs()
  val stateFile = folder + "state.dat"
  new File(stateFile).delete()

  val wallet = new Wallet(None, "123", Some(Array(0.toByte, 1.toByte)))
  val accounts = wallet.generateNewAccounts(3)

  val db = new MVStore.Builder().fileName(stateFile).compress().open()
  val state = new StoredState(db)
  state.processBlock(new BlockMock(Seq(GenesisTransaction(accounts.head, 100000000000L, 0))))

  private def issueAsset(request: IssueRequest, wallet: Wallet): IssueTransaction = {
    val sender = wallet.privateKeyAccount(request.sender).get
    IssueTransaction.create(sender,
      None,
      Base58.decode(request.name).get,
      Base58.decode(request.description).get,
      request.quantity,
      request.decimals,
      request.reissuable,
      request.fee,
      System.currentTimeMillis())
  }

  def transferAsset(request: TransferRequest, wallet: Wallet): TransferTransaction = {
    val sender = wallet.privateKeyAccount(request.sender).get
    TransferTransaction.create(request.assetIdOpt.map(s => Base58.decode(s).get),
      sender: PrivateKeyAccount,
      new Account(request.recipient),
      request.amount,
      System.currentTimeMillis(),
      request.feeAsset.map(s => Base58.decode(s).get),
      request.feeAmount,
      Base58.decode(request.attachment).get)
  }

  test("many transactions") {
    val acc = accounts.head
    val senderBalance = state.balance(acc)

    val receipements = Seq(
      new PrivateKeyAccount(Array(34.toByte, 1.toByte)),
      new PrivateKeyAccount(Array(1.toByte, 23.toByte))
    )

    val issueAssetTx = issueAsset(IssueRequest(acc.address, "AAAAB", "BBBBB", 1000000, 2, reissuable = false, 100000000), wallet)
    state.processBlock(new BlockMock(Seq(issueAssetTx))) should be('success)
    val assetId = Some(Base58.encode(issueAssetTx.assetId))

    val txs = receipements.flatMap(r => Seq.fill(10) {
      Thread.sleep(1)
      transferAsset(TransferRequest(assetId, None, 10, 1 , acc.address, "123", r.address), wallet)
    })

    val shuffledTxs = Random.shuffle(txs)

    state.processBlock(new BlockMock(shuffledTxs)) should be('success)

    receipements.foreach(r => state.assetBalance(AssetAcc(r, Some(issueAssetTx.assetId))) should be (100))

    state.assetBalance(AssetAcc(acc, Some(issueAssetTx.assetId))) should be (999800)
  }
}
