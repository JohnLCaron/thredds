package ucar.nc2.ft.coverage;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.constants.AxisType;
import ucar.nc2.ft2.coverage.*;
import ucar.nc2.grib.collection.GribDataReader;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.util.Misc;
import ucar.nc2.util.Optional;
import ucar.unidata.test.util.NeedsCdmUnitTest;
import ucar.unidata.test.util.TestDir;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mostly checks GeoReferencedArray matches request
 *
 * @author John
 * @since 8/15/2015
 */
@RunWith(Parameterized.class)
@Category(NeedsCdmUnitTest.class)
public class TestCoverageSubset {

  @BeforeClass
  public static void before() {
    GribDataReader.validator = new GribCoverageValidator();
  }

  @AfterClass
  public static void after() {
    GribDataReader.validator = null;
  }


  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> getTestParameters() {
    List<Object[]> result = new ArrayList<>();

    result.add(new Object[]{TestDir.cdmUnitTestDir + "gribCollections/gfs_2p5deg/gfs_2p5deg.ncx3", "Momentum_flux_u-component_surface_Mixed_intervals_Average",
            "2015-03-01T06:00:00Z", "2015-03-01T12:00:00Z", null, null});   // time

    result.add(new Object[]{TestDir.cdmUnitTestDir + "gribCollections/gfs_2p5deg/gfs_2p5deg.ncx3", "Momentum_flux_u-component_surface_Mixed_intervals_Average",
            "2015-03-01T06:00:00Z", null, 213.0, null});                 // time offset

    result.add(new Object[]{TestDir.cdmUnitTestDir + "gribCollections/gfs_2p5deg/gfs_2p5deg.ncx3", "Ozone_Mixing_Ratio_isobaric",
            "2015-03-01T06:00:00Z", null, 213.0, null});   // all levels

    result.add(new Object[]{TestDir.cdmUnitTestDir + "gribCollections/gfs_2p5deg/gfs_2p5deg.ncx3", "Ozone_Mixing_Ratio_isobaric",
            "2015-03-01T06:00:00Z", null, 213.0, 10000.});   // specific level

// */
    return result;
  }

  String endpoint, covName;
  CalendarDate rt_val, time_val;
  Double time_offset, vert_level;

  public TestCoverageSubset(String endpoint, String covName, String rt_val, String time_val, Double time_offset, Double vert_level) {
    this.endpoint = endpoint;
    this.covName = covName;
    this.rt_val = (rt_val == null) ? null : CalendarDate.parseISOformat(null, rt_val);
    this.time_val = (time_val == null) ? null : CalendarDate.parseISOformat(null, time_val);
    this.time_offset = time_offset;
    this.vert_level = vert_level;
  }

  @Test
  public void testGridCoverageDatasetFmrc() throws IOException, InvalidRangeException {
    System.out.printf("Test Dataset %s%n", endpoint);

    try (CoverageDatasetCollection cc = CoverageDatasetFactory.open(endpoint)) {
      Assert.assertNotNull(endpoint, cc);
      CoverageDataset gcs = cc.findCoverageDataset(CoverageCoordSys.Type.Fmrc);
      if (gcs == null) return;

      Coverage cover = gcs.findCoverage(covName);
      Assert.assertNotNull(covName, cover);

      readOne(cover, rt_val, time_val, time_offset, vert_level);
    }
  }

  @Test
  public void testGridCoverageDatasetBest() throws IOException, InvalidRangeException {
    System.out.printf("Test Dataset %s%n", endpoint);

    try (CoverageDatasetCollection cc = CoverageDatasetFactory.open(endpoint)) {
      Assert.assertNotNull(endpoint, cc);
      CoverageDataset gcs = cc.findCoverageDataset(CoverageCoordSys.Type.Grid);
      Assert.assertNotNull("gcs", gcs);
      Coverage cover = gcs.findCoverage(covName);
      Assert.assertNotNull(covName, cover);

      // Cant subset on runtime for Best, so we set to null
      readOne(cover, null, time_val, time_offset, vert_level);
    }
  }

  @Test
  public void testBestStride() throws IOException, InvalidRangeException {
    System.out.printf("Test Dataset %s%n", endpoint);

    try (CoverageDatasetCollection cc = CoverageDatasetFactory.open(endpoint)) {
      Assert.assertNotNull(endpoint, cc);
      CoverageDataset gcs = cc.findCoverageDataset(CoverageCoordSys.Type.Grid);
      Assert.assertNotNull("gcs", gcs);
      Coverage cover = gcs.findCoverage(covName);
      Assert.assertNotNull(covName, cover);
      CoverageCoordSys csys = cover.getCoordSys();
      int[] csysShape =  csys.getShape();
      System.out.printf("csys shape = %s%n", Misc.showInts(csysShape));

      SubsetParams params = new SubsetParams().set(SubsetParams.horizStride, 2);
      Optional<CoverageCoordSysSubset> csysSubseto = csys.subset(params, false);
      if (!csysSubseto.isPresent()) {
        System.out.printf("err=%s%n", csysSubseto.getErrorMessage());
        assert false;
      }

      CoverageCoordSys csysSubset = csysSubseto.get().coordSys;
      int[] subsetShape =  csysSubset.getShape();
      System.out.printf("csysSubset shape = %s%n", Misc.showInts(subsetShape));

      int n = csysShape.length;
      csysShape[n-1] = (csysShape[n-1]+1)/2;
      csysShape[n-2] = (csysShape[n-2]+1)/2;

      Assert.assertArrayEquals(csysShape, subsetShape);
    }
  }

  void readOne(Coverage cover, CalendarDate rt_val, CalendarDate time_val, Double time_offset, Double vert_level) throws IOException, InvalidRangeException {
    System.out.printf("%n===Request Subset %s runtime=%s time=%s timeOffset=%s vert=%s %n", cover.getName(), rt_val, time_val, time_offset, vert_level);

    SubsetParams subset = new SubsetParams();
    if (rt_val != null)
      subset.set(SubsetParams.runtime, rt_val);
    if (time_val != null)
      subset.set(SubsetParams.time, time_val);
    if (time_offset != null)
      subset.set(SubsetParams.timeOffset, time_offset);
    if (vert_level != null)
      subset.set(SubsetParams.vertCoord, vert_level);

    GeoReferencedArray geoArray = cover.readData(subset);
    CoverageCoordSys geoCs = geoArray.getCoordSysForData();
    System.out.printf("%n%s%n", geoArray);
    System.out.printf("%ngeoArray shape=%s%n", Misc.showInts(geoArray.getData().getShape()));

    if (rt_val != null) {
      CoverageCoordAxis runAxis = geoCs.getAxis(AxisType.RunTime);
      if (runAxis != null) {
        Assert.assertEquals(1, runAxis.getNcoords());
        double val = runAxis.getStartValue();
        CalendarDate runDate = runAxis.makeDate(val);
        Assert.assertEquals(rt_val, runDate);
      }
    }

    if (time_val != null || time_offset != null) {
      CoverageCoordAxis timeAxis = geoCs.getAxis(AxisType.TimeOffset);
      if (timeAxis != null) {
        TimeOffsetAxis timeOffsetAxis = (TimeOffsetAxis) timeAxis;
        CoverageCoordAxis1D runAxis = (CoverageCoordAxis1D) geoCs.getAxis(AxisType.RunTime);
        Assert.assertNotNull(AxisType.RunTime.toString(), runAxis);
        Assert.assertEquals(1, runAxis.getNcoords());
        double val = runAxis.getStartValue();
        CalendarDate runDate = runAxis.makeDate(val);
        Assert.assertEquals(rt_val, runDate);
        Assert.assertEquals(1, timeOffsetAxis.getNcoords());

        if (time_val != null) {
          if (timeOffsetAxis.isInterval()) {
            CalendarDate edge1 = timeOffsetAxis.makeDate(timeOffsetAxis.getCoordEdge1(0));
            CalendarDate edge2 = timeOffsetAxis.makeDate(timeOffsetAxis.getCoordEdge2(0));

            Assert.assertTrue(edge1+">"+time_val, !edge1.isAfter(time_val));
            Assert.assertTrue(edge2+"<"+time_val, !edge2.isBefore(time_val));

          } else {
            double val2 = timeOffsetAxis.getCoord(0);
            CalendarDate forecastDate = timeOffsetAxis.makeDate(runDate, val2);
            Assert.assertEquals(time_val, forecastDate);
          }

        } else {
          if (timeOffsetAxis.isInterval()) {
            Assert.assertTrue(timeOffsetAxis.getCoordEdge1(0) <= time_offset);
            Assert.assertTrue(timeOffsetAxis.getCoordEdge2(0) >= time_offset);

          } else {
            double val2 = timeOffsetAxis.getCoord(0);
            Assert.assertEquals(val2, time_offset, Misc.maxReletiveError);
          }
        }
      }

      if (vert_level != null) {
        CoverageCoordAxis zAxis = geoCs.getZAxis();
        Assert.assertNotNull(AxisType.Pressure.toString(), zAxis);
        Assert.assertEquals(1, zAxis.getNcoords());
        double val = zAxis.getStartValue();
        Assert.assertEquals(vert_level.doubleValue(), val, Misc.maxReletiveError);
      }
    }

  }

}