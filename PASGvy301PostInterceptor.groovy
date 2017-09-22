import com.navis.argo.*
import com.navis.argo.BookingTransactionDocument.BookingTransaction
import com.navis.argo.BookingTransactionsDocument.BookingTransactions
import com.navis.argo.business.api.ArgoUtils
import com.navis.argo.business.api.VesselVisitFinder
import com.navis.argo.business.atoms.BizRoleEnum
import com.navis.argo.business.atoms.DataSourceEnum
import com.navis.argo.business.model.CarrierVisit
import com.navis.argo.business.model.Complex
import com.navis.argo.business.model.Facility
import com.navis.argo.business.model.GeneralReference
import com.navis.argo.business.reference.EquipType
import com.navis.argo.business.reference.LineOperator
import com.navis.argo.business.reference.ScopedBizUnit
import com.navis.edi.EdiEntity
import com.navis.edi.EdiField
import com.navis.edi.business.edimodel.EdiConsts
import com.navis.edi.business.util.X12Util
import com.navis.external.edi.entity.AbstractEdiPostInterceptor
import com.navis.framework.AllOtherFrameworkPropertyKeys
import com.navis.framework.business.Roastery
import com.navis.framework.persistence.HibernateApi
import com.navis.framework.persistence.HibernatingEntity
import com.navis.framework.portal.Ordering
import com.navis.framework.portal.QueryUtils
import com.navis.framework.portal.UserContext
import com.navis.framework.portal.query.DomainQuery
import com.navis.framework.portal.query.PredicateFactory
import com.navis.framework.query.common.api.QueryResult
import com.navis.framework.util.BizFailure
import com.navis.framework.util.BizViolation
import com.navis.framework.util.unit.TemperatureUnit
import com.navis.framework.util.unit.UnitUtils
import com.navis.inventory.business.api.UnitFinder
import com.navis.inventory.business.units.Unit
import com.navis.orders.OrdersPropertyKeys
import com.navis.orders.business.api.OrdersFinder
import com.navis.orders.business.eqorders.Booking
import com.navis.orders.business.eqorders.EquipmentOrderItem
import com.navis.road.business.util.RoadBizUtil
import org.apache.commons.lang.StringUtils
import org.apache.commons.lang.math.NumberUtils
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.xmlbeans.XmlObject

import static java.util.Arrays.asList

/*
 * Copyright (c) 2014 Navis LLC. All Rights Reserved.
 *
 */

/**
 * <Purpose>
 * Allows action New to update a booking if it exists
 * Allows action Update to create a booking if it doesn't exist
 *
 * Author: <a href="mailto:freddie.ledfors@navis.com"> fledfors</a>
 * Date: 04/08/15
 * JIRA: CSDV-3019
 * SFDC: NONE
 * Called from: EDI Session Post Interceptor
 *
 * Triggering the Groovy:
 * a)            Administration >> EDI >> EDI Sessions (If this menu is not available navigate to EDI Sessions by using EDI Configuration Menu).
 * b)            Right Click on the EDI session where you want to invoke this groovy (PAS 301 session for example).
 * c)            Click on Edit.
 * d)            Click on Post Code Extension and Choose PASGvy301PostInterceptor.
 * e)            Click on Save. The session should be as shown below.
 *
 * ---------------------------------------------------------------------------------------------------------------------------------------------------
 * Revision History
 * ---------------------------------------------------------------------------------------------------------------------------------------------------
 * 09/29/2016, CSDV-4057, Case: 00156518 , bbakthavachalam: Update the container owner and opr associated with booking to Vessel Sharing Agreement
 * partner codes.
 *
 * 10/28/2016, CSDV-4057, Case: 00156518 , bbakthavachalam: Update the container line to PIL if the Vessel Sharing Agreement is PIL
 *
 * 12/20/2016, CSDV-4055, Case: 00156518 , bbakthavachalam: Update Booking Line
 *
 * 05/30/2017, CSDV-4523, Case: 00170604 , bbakthavachalam: Post Booking Item's tail temp
 * 07/10/2017  CSDV-4612  170604  Allen Hsieh  Modify script to handle tail and head temperatures with plus sign and value of "999"
 * 07/19/2017  CSDV-4612  170604  Allen Hsieh  Modify script to support updating tail temperature with multiple bookings in a 301 EDI file
 * 08/11/2017  CSDV-4612  170604  Allen Hsieh  Modified script to skip temperature update if EDI message funciton is "D"
 * 
 **/

public class PASGvy301PostInterceptor extends AbstractEdiPostInterceptor {

  UserContext context = ContextHelper.getThreadUserContext();
  Date timeNow = ArgoUtils.convertDateToLocalDateTime(ArgoUtils.timeNow(), context.getTimeZone());

  @Override
  public void beforeEdiPost(Serializable inSessionGkey, XmlObject inXmlObject) throws BizViolation {
    LOGGER.warn("in PASGvy301PostInterceptor Started" + timeNow);
    if (inXmlObject == null) {
      LOGGER.warn("Input XML Object is null");
      throw BizFailure.create(OrdersPropertyKeys.ERRKEY__NULL_XMLBEAN, null);
    }

    if (!BookingTransactionsDocument.class.isAssignableFrom(inXmlObject.getClass())) {
      throw BizFailure.create(OrdersPropertyKeys.ERRKEY__TYPE_MISMATCH_XMLBEAN, null, inXmlObject.getClass().getName());
    }

    BookingTransactionsDocument bkgDocument = (BookingTransactionsDocument) inXmlObject;
    final BookingTransactions bkgtrans = bkgDocument.getBookingTransactions();

    final BookingTransaction[] bkgtransArray = bkgtrans.getBookingTransactionArray();

    if (bkgtransArray.length != 1) {
      throw BizFailure.create(OrdersPropertyKeys.ERRKEY__XML_TRANSACTION_DOCUMENT_LENGTH_EXCEED, null, String.valueOf(bkgtransArray.length));
    }
    BookingTransaction bkgTrans = bkgtransArray[0];
    try {

      EdiOperator ediOp = bkgTrans.getLineOperator();
      Facility facility = ContextHelper.getThreadFacility();
      Complex complex = ContextHelper.getThreadComplex();
      updateOwnerAndOpr(bkgtransArray[0], book);
      EdiVesselVisit EdiVv = bkgTrans.getEdiVesselVisit();
      ScopedBizUnit bkgLineOp = this.resolveLineOperator(EdiVv, ediOp);
      CarrierVisit ediCv = this.resolveCarrierVisit(EdiVv, complex, facility, bkgLineOp);
      Booking book = this.getBookingDetails(bkgTrans, ediCv, bkgLineOp);
      this.checkMsgFunctionCode(bkgTrans, book);
      // Booking exists against Vessel 1 and a delete was sent against Vessel 2 and tally count > 0 then throw an error.
    } catch (Exception e) {
      LOGGER.warn("Error while processing before edi post");
    }

    LOGGER.warn("in PASGvy301PostInterceptor Ended" + timeNow);
  }

  @Override
  public void afterEdiPost(XmlObject inXmlObject, HibernatingEntity inHibernatingEntity, Map inParams) throws BizViolation {
    LOGGER.setLevel(Level.INFO);
    LOGGER.info("in PASGvy301PostInterceptor after EDI Post Started");
    if (!BookingTransactionsDocument.class.isAssignableFrom(inXmlObject.getClass())) {
      throw BizFailure.create(OrdersPropertyKeys.ERRKEY__TYPE_MISMATCH_XMLBEAN, null, inXmlObject.getClass().getName());
    }
    BookingTransactionsDocument bkgDocument = (BookingTransactionsDocument) inXmlObject;
    final BookingTransactions bkgtrans = bkgDocument.getBookingTransactions();
    final BookingTransaction[] bkgtransArray = bkgtrans.getBookingTransactionArray();
    if (bkgtransArray.length != 1) {
      throw BizFailure.create(OrdersPropertyKeys.ERRKEY__XML_TRANSACTION_DOCUMENT_LENGTH_EXCEED, null, String.valueOf(bkgtransArray.length));
    }
    BookingTransaction bkgTrans = bkgtransArray[0];
    EdiOperator ediOp = bkgTrans.getLineOperator();
    Facility facility = ContextHelper.getThreadFacility();
    Complex complex = ContextHelper.getThreadComplex();
    EdiVesselVisit EdiVv = bkgTrans.getEdiVesselVisit();
    ScopedBizUnit bkgLineOp = resolveLineOperator(EdiVv, ediOp);
    CarrierVisit ediCv = resolveCarrierVisit(EdiVv, complex, facility, bkgLineOp);
    Booking book = getBookingDetails(bkgTrans, ediCv, bkgLineOp);
    String msgFunctionCode = bkgTrans.getMsgFunction();
    //log("msgFunctionCode= " + msgFunctionCode);
    //Don't call update methods if EDI message funciton is "D" - deletion
    if (book != null && !StringUtils.equalsIgnoreCase(msgFunctionCode, "D")) {
      //updateOwnerAndOpr(bkgtransArray[0], book);
      updateBookinItemsTailTemperature(book, inParams);
      updateBookingItemsTempRequiredField(book, bkgTrans);
    }
    LOGGER.info("in PASGvy301PostInterceptor after EDI Post Completed.");
  }

  /**
   *
   * @param inBooking
   * @param inBkgTrans
   */
  private void updateBookingItemsTempRequiredField(Booking inBooking, BookingTransaction inBkgTrans) {

    log("updateBookingItemsTempRequiredField - starts");
    if (inBkgTrans == null) {
      return;
    }

    BookingTransaction.EdiBookingItem[] bkgitemArray = inBkgTrans.getEdiBookingItemArray();
    if (bkgitemArray == null) {
      return;
    }

    for (int count = 0; count < bkgitemArray.length; count++) {
      String eqIso = bkgitemArray[count].getISOcode();
      if (StringUtils.isBlank(eqIso)) {
        continue;
      }

      EquipType eqType = EquipType.findEquipType(eqIso);
      if (eqType == null) {
        continue;
      }

      // Find the eqoi and if found update the quantity
      String ediSeqNbr = bkgitemArray[count].getSequenceNumber();
      Long seqNumber = safeGetQty(ediSeqNbr);
      EquipmentOrderItem eqoi;
      if (seqNumber == null) {
        eqoi = resolveBookingOrderItemByType(inBooking, eqType);
      } else {
        eqoi = resolveBookingOrderItemBySeqNbr(inBooking, seqNumber);
      }

      if (eqoi == null) {
        continue;
      }

      Double bkgTempC = eqoi.getEqoiTempRequired();
      if (bkgTempC == null) {
        continue;
      }

      String prefTemp = getTempRequiredString(bkgTempC, true);
      if (StringUtils.contains(prefTemp, "999")) {
        eqoi.setEqoiTempRequired(null);
        log("updateBookingItemsTempRequiredField - set Temp Required to blank due to its value= " + prefTemp);
      }
    }

    log("updateBookingItemsTempRequiredField - ends");
  }

  /**
   *
   * @param inTempC
   * @return
   */
  private String getTempRequiredString(Double inTempC, boolean convertToF) {

    if (inTempC == null) {
      return null;
    }

    double tempRequiredF = inTempC;
    if (convertToF) {
      tempRequiredF = UnitUtils.convertTo(inTempC.doubleValue(), TemperatureUnit.C, TemperatureUnit.F);
    }
    String tempRequiredStr = null;
    try {
      tempRequiredStr = (tempRequiredF != null) ? tempRequiredF.toString() : null;
    } catch (Exception e) {
      tempRequiredStr = null;
    }

    log("getTempRequiredString - tempRequiredStr= " + tempRequiredStr);
    return tempRequiredStr;
  }

  private static Long safeGetQty(String inNumberString) {
   if (NumberUtils.isNumber(inNumberString)) {
      return Math.round(Double.parseDouble(inNumberString));
    }
    return null;
  }

  private static EquipmentOrderItem resolveBookingOrderItemBySeqNbr(Booking inBook, Long inSeqNbr) {
    return getOrdersFinder().findEqoItemBySequenceNbr(inBook, inSeqNbr);
  }

  private static EquipmentOrderItem resolveBookingOrderItemByType(Booking inBook, EquipType inEqType) {
    return getOrdersFinder().
            findEqoItemByEqType(inBook, inEqType.getEqtypNominalLength(), inEqType.getEqtypIsoGroup(), inEqType.getEqtypNominalHeight(),
                    DataSourceEnum.EDI_BKG);
  }

  private static OrdersFinder getOrdersFinder() {
    return (OrdersFinder) Roastery.getBean(OrdersFinder.BEAN_ID);
  }

  /**
   * Update Booking Item Tail Temp
   * @param inBooking
   * @param inParams
   */
  private void updateBookinItemsTailTemperature(Booking inBooking, Map inParams) {
    LOGGER.info("Calling updateBookinItemsTailTemperature method.");
    Serializable batchGkey = inParams.get(EdiConsts.BATCH_GKEY);
    List<String> segmentList = findEdiSegments(batchGkey);
    String y4Segment = null;
    String w9Segment = null;
    String y3Segment = null;
    for (String segment : segmentList) {
      if (segment == null) {
        continue;
      }
      if (segment.startsWith("Y3*")) {
        y3Segment = segment;
      }
      if (segment.startsWith("Y4*")) {
        y4Segment = segment;
      }
      if (segment.startsWith("W09*")) {
        w9Segment = segment;

        //check tail temp Exist
        String[] w9Token = StringUtils.splitPreserveAllTokens(w9Segment, "*");
        String[] y3Token = StringUtils.splitPreserveAllTokens(y3Segment, "*");
        if (y4Segment != null && w9Token.length >= 11) {
          if (y3Token.length >= 7) {
            String bookingStr = X12Util.parse(y3Segment, 1);
            log("bookingStr= " + bookingStr);
            if (!StringUtils.equalsIgnoreCase(bookingStr, inBooking.getEqboNbr())) {
              log("Continue as booking nbr does not match!");
              continue;
            }
          }
          String tailTemp = X12Util.parse(w9Segment, 10);
          if (tailTemp.startsWith("+")) {
            tailTemp = StringUtils.substring(tailTemp, 1);
          }

          if (StringUtils.isNotBlank(tailTemp) && NumberUtils.isNumber(tailTemp.trim())) {
            //find equip type
            String[] y4Token = StringUtils.splitPreserveAllTokens(y4Segment, "*");
            if (y4Token.length > 6) {
              String eqType = X12Util.parse(y4Segment, 6);
              if (eqType.endsWith("~")) {
                eqType = eqType.substring(0, eqType.length() - 1);
              }
              EquipType equipType = EquipType.findEquipType(eqType);
              EquipmentOrderItem item = _ordersFinder.findEqoItemByEqType(inBooking, equipType);
              if (item != null) {
                if (StringUtils.contains(tailTemp, "999")) {
                  item.setFieldValue("eqoiCustomFlexFields.eqoiCustomDFFTailTemp", null);
                  LOGGER.info("Updated tail temp to null" + " for the booking:" + inBooking.getEqboNbr());
                } else {
                  item.setFieldValue("eqoiCustomFlexFields.eqoiCustomDFFTailTemp", Double.parseDouble(tailTemp.trim()));
                  LOGGER.info("Updated tail temp:" + tailTemp + " for the booking:" + inBooking.getEqboNbr());
                }
              } else {
                LOGGER.error("Booking Item not found in booking:" + inBooking.getEqboNbr() + " for ISO type:" + eqType);
              }
            }
          }
        }
      }
    }
    LOGGER.info("Completed Calling updateBookinItemsTailTemperature method.");
  }

  /**
   * Update the container owner and opr associated with booking to Vessel Sharing Agreement partner codes.
   * @param inBkgTrans
   * @param inBooking
   */
  private void updateOwnerAndOpr(BookingTransaction inBkgTrans, Booking inBooking) {
    FlexStringFields flexStringFields = inBkgTrans.getFlexStringFields();
    if (flexStringFields == null || flexStringFields.getFlexString07() == null) {
      LOGGER.info("Booking: " + inBooking.getEqboNbr() +
              " has no Vessel Sharing Code in EDI file, hence the conatiners Opr and Owner associated with this booking are not updated to Vessel Sharing Code.");
      return;
    }
    String partnerCode = flexStringFields.getFlexString07();
    def pashaUpdateOwnerAndOprLibrary = getLibrary("PashaUpdateOwnerAndOprLibrary");
    if (pashaUpdateOwnerAndOprLibrary == null) {
      LOGGER.error(" PASGvy301PostInterceptor, Couldn't find the groovy PashaUpdateOwnerAndOprLibrary.");
      return;
    }
    GeneralReference generalReference = GeneralReference.findUniqueEntryById("EDI", "VSL_SHARING", "PARTNER_CODES");
    if (generalReference == null || generalReference.getRefValue1() == null) {
      throw BizViolation.create(AllOtherFrameworkPropertyKeys.ERROR__NULL_MESSAGE, null,
              "Please configure a General Reference in N4 for the Type:EDI, Identifier 1:VSL_SHARING and Identifier 2:PARTNER_CODES");
    }
    List valueList = new ArrayList(asList(generalReference.getRefValue1().split(',')));
    List lineList = generalReference.getRefValue2() != null ? new ArrayList(asList(generalReference.getRefValue2().split(','))) : null;
    if (valueList.contains(partnerCode)) {
      LOGGER.info("PASGvy301PostInterceptor, about to execute PashaUpdateOwnerAndOprLibrary for the Booking:" + inBooking.getEqboNbr());
      List<Unit> unitList = getUnitFinder().findUnitsAdvisedOrReceivedForOrder(inBooking);
      pashaUpdateOwnerAndOprLibrary.updateOwnerAndOpr(unitList, partnerCode);
      LOGGER.info("PASGvy301PostInterceptor, completed calling PashaUpdateOwnerAndOprLibrary for the Booking:" + inBooking.getEqboNbr());
    }
    if (lineList != null && lineList.contains(partnerCode)) {
      LOGGER.info("PASGvy301PostInterceptor, about to execute PashaUpdateOwnerAndOprLibrary for the Booking:" + inBooking.getEqboNbr());
      List<Unit> unitList = getUnitFinder().findUnitsAdvisedOrReceivedForOrder(inBooking);
      pashaUpdateOwnerAndOprLibrary.updateLine(unitList, partnerCode);
      //Update Booking Line
      ScopedBizUnit line = LineOperator.resolveScopedBizUnit(partnerCode, null, BizRoleEnum.LINEOP);
      if (line != null) {
        //inBooking.setEqoLine(line);
        String scac = line.getBzuScac();
        EdiVesselVisit ediVvd = inBkgTrans.getEdiVesselVisit();
        if (ediVvd != null) {
          ShippingLine shippingLine = ediVvd.getShippingLine();
          if (shippingLine != null) {
            shippingLine.setShippingLineCode(scac);
          }
        }
      }
      LOGGER.info("PASGvy301PostInterceptor, completed calling PashaUpdateOwnerAndOprLibrary for the Booking:" + inBooking.getEqboNbr());
    }
  }

  private static UnitFinder getUnitFinder() {
    return (UnitFinder) Roastery.getBean(UnitFinder.BEAN_ID);
  }

  private CarrierVisit resolveCarrierVisit(EdiVesselVisit inEdiVv, Complex complex, Facility inFacility, ScopedBizUnit bkgLineOp)
          throws BizViolation {
    if (complex == null) {
      LOGGER.warn(" Thread Complex is Null")
    }
    String vvConvention = null
    String vvId = null
    final String ibVoyg = null
    final String obVoyg = null
    if (inEdiVv != null) {
      vvConvention = inEdiVv.getVesselIdConvention()
      vvId = inEdiVv.getVesselId()
      ibVoyg = inEdiVv.getInVoyageNbr()
      if (ibVoyg == null) {
        ibVoyg = inEdiVv.getInOperatorVoyageNbr()
      }
      obVoyg = inEdiVv.getOutVoyageNbr()
      if (obVoyg == null) {
        obVoyg = inEdiVv.getOutOperatorVoyageNbr()
      }
    }

    CarrierVisit cv
    VesselVisitFinder vvf = (VesselVisitFinder) Roastery.getBean(VesselVisitFinder.BEAN_ID)
    // Note: This will throw a BizViolation if the vessel visit can not be found
    LOGGER.warn('Convention ' + vvConvention + ' vvId' + vvId + " voyage " + ibVoyg)
    if (ibVoyg != null) {
      cv = vvf.findVesselVisitForInboundStow(complex, vvConvention, vvId, ibVoyg, null, null)
    } else {
      cv = vvf.findOutboundVesselVisit(complex, vvConvention, vvId, obVoyg, bkgLineOp, null)
    }

    LOGGER.warn(cv)
    return cv
  }

  private ScopedBizUnit resolveLineOperator(EdiVesselVisit inEdiVesselVisit, EdiOperator inEdiOperator) {
    LOGGER.warn(" in Resolve Line Operator")
    ScopedBizUnit inLine = null

    String lineCode
    String lineCodeAgency

    try {

      if (inEdiOperator != null) {
        lineCode = inEdiOperator.getOperator()
        lineCodeAgency = inEdiOperator.getOperatorCodeAgency()
        inLine = ScopedBizUnit.resolveScopedBizUnit(lineCode, lineCodeAgency, BizRoleEnum.LINEOP)
      }

      if (inLine == null && inEdiVesselVisit != null && inEdiVesselVisit.getShippingLine() != null) {
        lineCode = inEdiVesselVisit.getShippingLine().getShippingLineCode()
        lineCodeAgency = inEdiVesselVisit.getShippingLine().getShippingLineCodeAgency()
        inLine = ScopedBizUnit.resolveScopedBizUnit(lineCode, lineCodeAgency, BizRoleEnum.LINEOP)
      }
    } catch (Exception e) {
      LOGGER.warn("Cannot Resolve Line Operator" + e)
    }
    return inLine
  }

  private Booking checkBooking(String inBkgNbr, ScopedBizUnit inLineOperator, CarrierVisit inCv) {
    LOGGER.warn(" in Check Booking")
    if (inBkgNbr == null) {
      LOGGER.warn("inBkgNbr is null")
    }
    if (inLineOperator == null) {
      LOGGER.warn("inLineOperator is Null")
    }
    if (inCv == null) {
      LOGGER.warn("inCv is Null")
    }
    Booking bkg = Booking.findBookingByUniquenessCriteria(inBkgNbr, inLineOperator, inCv)
    LOGGER.warn("bkg:" + bkg.toString())
    return bkg
  }

  private Booking getBookingDetails(BookingTransaction inBkgTrans, CarrierVisit inCv, ScopedBizUnit inBkgLineOp) {

    EdiBooking bkgNbr = inBkgTrans.getEdiBooking()
    String bookingNumber = bkgNbr.getBookingNbr()
    Booking book = null
    log("getBookingDetails - bkgNbr= " + bookingNumber);
    try {
      book = this.checkBooking(bookingNumber, inBkgLineOp, inCv)
      return book
    } catch (Exception e) {
      LOGGER.warn(" Exception:" + e)
      return book
    }
  }

  private void checkMsgFunctionCode(BookingTransaction inBkgTrans, Booking inBook) throws BizViolation {
    String msgFunction = this.getMsgFunction(inBkgTrans);
    if (msgFunction == null) {
      LOGGER.warn("msgFunction is Null");
      this.reportUserError("Message Function cannot be Null");
      return;
    }
    if (msgFunction.equalsIgnoreCase("D") || msgFunction.equalsIgnoreCase("R") || msgFunction.equalsIgnoreCase("E") ||
            msgFunction.equalsIgnoreCase("X")) {
      LOGGER.warn("Booking Cancel is received No Action is Taken");
      return;
    }
    LOGGER.warn("msgFunction:" + msgFunction);
    if (inBook == null) {
      inBkgTrans.setMsgFunction("O");
      LOGGER.warn("Msg Function:" + "O");
    } else {
      inBkgTrans.setMsgFunction("C");
      LOGGER.warn("Msg Function:" + "C");
      //set msgFunction to Changed
    }
  }

  private List<String> findEdiSegments(Serializable inBatchGkey) {
    List<String> resultList = new ArrayList<>();
    DomainQuery dq = QueryUtils.createDomainQuery(EdiEntity.EDI_SEGMENT)
            .addDqField(EdiField.EDISEG_SEGMENT)
            .addDqPredicate(PredicateFactory.eq(EdiField.EDISEG_BATCH, inBatchGkey))
            .addDqOrdering(Ordering.asc(EdiField.EDISEG_SEQ));
    QueryResult qr = HibernateApi.getInstance().findValuesByDomainQuery(dq);
    for (int i = 0; i < qr.getTotalResultCount(); i++) {
      resultList.add((String) qr.getValue(i, EdiField.EDISEG_SEGMENT));
    }
    return resultList;
  }

  private String getMsgFunction(BookingTransaction inBkgTrans) {
    return inBkgTrans.getMsgFunction();
  }

  // Adds an error to the list of errors that will be displayed
  private void reportUserError(String message) {
    RoadBizUtil.messageCollector.appendMessage(BizFailure.create(message));
  }

  private static final Logger LOGGER = Logger.getLogger(PASGvy301PostInterceptor.class);
  OrdersFinder _ordersFinder = (OrdersFinder) Roastery.getBean(OrdersFinder.BEAN_ID);
}