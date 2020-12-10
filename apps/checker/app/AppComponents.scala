import java.io.File

import scala.concurrent.Future
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProvider, AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.gu.contentapi.client.GuardianContentClient
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.gu.pandomainauth.PublicSettings
import com.gu.{AppIdentity, AwsIdentity, DevIdentity}
import controllers.{ApiController, AuditController, CapiProxyController, HomeController, RulesController}
import matchers.RegexMatcher
import play.api.ApplicationLoader.Context
import play.api.BuiltInComponentsFromContext
import play.api.http.{DefaultHttpErrorHandler, JsonHttpErrorHandler, PreferredMediaTypeHttpErrorHandler}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.libs.concurrent.DefaultFutures
import play.api.mvc.EssentialFilter
import play.filters.HttpFiltersComponents
import play.filters.cors.CORSComponents
import router.Routes
import rules.{BucketRuleManager, SheetsRuleManager}
import services._
import com.gu.typerighter.lib.{Loggable, ElkLogging}
import matchers.LanguageToolFactory
import utils.CloudWatchClient


class AppComponents(context: Context, identity: AppIdentity, creds: AWSCredentialsProvider)
  extends BuiltInComponentsFromContext(context)
  with HttpFiltersComponents
  with CORSComponents
  with Loggable
  with controllers.AssetsComponents
  with AhcWSComponents {

  override def httpFilters: Seq[EssentialFilter] = corsFilter +: super.httpFilters.filterNot(allowedHostsFilter ==)

  // initialise log shipping if we are in AWS
  private val logShipping = Some(identity).collect{ case awsIdentity: AwsIdentity =>
    val loggingStreamName = configuration.getOptional[String]("typerighter.loggingStreamName")
    new ElkLogging(awsIdentity, loggingStreamName, creds, applicationLifecycle)
  }

  val ngramPath: Option[File] = configuration.getOptional[String]("typerighter.ngramPath").map(new File(_))
  val languageToolFactory = new LanguageToolFactory(ngramPath, true)


  val capiApiKey = configuration.get[String]("capi.apiKey")
  val guardianContentClient = GuardianContentClient(capiApiKey)
  val contentClient = new ContentClient(guardianContentClient)

  private val s3Client = AmazonS3ClientBuilder.standard().withCredentials(creds).withRegion(AppIdentity.region).build()
  val settingsFile = identity match {
    case identity: AwsIdentity if identity.stage == "PROD" => "gutools.co.uk.settings.public"
    case identity: AwsIdentity => s"${identity.stage.toLowerCase}.dev-gutools.co.uk.settings.public"
    case _: DevIdentity => "local.dev-gutools.co.uk.settings.public"
  }
  val publicSettings = new PublicSettings(settingsFile, "pan-domain-auth-settings", s3Client)
  publicSettings.start()

  val stage = identity match {
    case identity: AwsIdentity => identity.stage.toLowerCase
    case _ => "code"
  }
  val typerighterBucket = s"typerighter-${stage}"

  val cloudWatchClient = identity match {
    case identity: AwsIdentity => new CloudWatchClient(stage, false)
    case _ : DevIdentity => new CloudWatchClient(stage, true)
  }

  val matcherPoolDispatcher = actorSystem.dispatchers.lookup("matcher-pool-dispatcher")
  val defaultFutures = new DefaultFutures(actorSystem)
  val matcherPool = new MatcherPool(futures = defaultFutures, maybeCloudWatchClient = Some(cloudWatchClient))(matcherPoolDispatcher, materializer)

  val bucketRuleManager = new BucketRuleManager(s3Client, typerighterBucket)
  val ruleProvisioner = new RuleProvisionerService(bucketRuleManager, matcherPool, languageToolFactory, cloudWatchClient)

  val credentials = configuration.get[String]("typerighter.google.credentials")
  val spreadsheetId = configuration.get[String]("typerighter.sheetId")
  val sheetsRuleManager = new SheetsRuleManager(credentials, spreadsheetId, matcherPool, languageToolFactory)

  val apiController = new ApiController(controllerComponents, matcherPool, publicSettings)
  val rulesController = new RulesController(controllerComponents, matcherPool, sheetsRuleManager, bucketRuleManager, spreadsheetId, ruleProvisioner, publicSettings)
  val homeController = new HomeController(controllerComponents, publicSettings)
  val auditController = new AuditController(controllerComponents, publicSettings)
  val capiProxyController = new CapiProxyController(controllerComponents, contentClient, publicSettings)

  override lazy val httpErrorHandler = PreferredMediaTypeHttpErrorHandler(
    "application/json" -> new JsonHttpErrorHandler(environment, None),
    "text/html" -> new DefaultHttpErrorHandler(),
  )

  lazy val router = new Routes(
    httpErrorHandler,
    assets,
    homeController,
    rulesController,
    auditController,
    apiController,
    capiProxyController
  )

  /**
    * Set up matchers and add them to the matcher pool as the app starts.
    */
  ruleProvisioner.scheduleUpdateRules(actorSystem.scheduler)
}