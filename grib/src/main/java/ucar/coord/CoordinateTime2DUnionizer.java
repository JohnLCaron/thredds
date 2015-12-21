/*
 * Copyright 1998-2015 John Caron and University Corporation for Atmospheric Research/Unidata
 *
 *  Portions of this software were developed by the Unidata Program at the
 *  University Corporation for Atmospheric Research.
 *
 *  Access and use of this software shall impose the following obligations
 *  and understandings on the user. The user is granted the right, without
 *  any fee or cost, to use, copy, modify, alter, enhance and distribute
 *  this software, and any derivative works thereof, and its supporting
 *  documentation for any purpose whatsoever, provided that this entire
 *  notice appears in all copies of the software, derivative works and
 *  supporting documentation.  Further, UCAR requests that the user credit
 *  UCAR/Unidata in any publications that result from the use of this
 *  software or in any product that includes this software. The names UCAR
 *  and/or Unidata, however, may not be used in any advertising or publicity
 *  to endorse or promote any products or commercial entity unless specific
 *  written permission is obtained from UCAR/Unidata. The user also
 *  understands that UCAR/Unidata is not obligated to provide the user with
 *  any support, consulting, training or assistance of any kind with regard
 *  to the use, operation and performance of this software nor to provide
 *  the user with any updates, revisions, new versions or "bug fixes."
 *
 *  THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 *  INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 *  FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 *  NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 *  WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package ucar.coord;

import org.slf4j.LoggerFactory;
import ucar.nc2.grib.TimeCoord;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.time.CalendarPeriod;
import ucar.nc2.util.Misc;

import java.util.*;

/**
 * Create union  CoordinateTime2D's.
 * Will build orthogonal and regular if possible.
 * Does not actually depend on T.
 *
 * @author caron
 * @since 11/22/2014
 */
class CoordinateTime2DUnionizer<T> extends CoordinateBuilderImpl<T> {
  // static private final Logger logger = LoggerFactory.getLogger(CoordinateTime2DUnionizer.class);

  boolean isTimeInterval;
  boolean makeVals;
  CalendarPeriod timeUnit;
  int code;
  org.slf4j.Logger logger;

  SortedMap<Long, CoordinateTimeAbstract> timeMap = new TreeMap<>();
  boolean shown;

  public CoordinateTime2DUnionizer(boolean isTimeInterval, CalendarPeriod timeUnit, int code,  boolean makeVals, org.slf4j.Logger logger) {
    this.isTimeInterval = isTimeInterval;
    this.timeUnit = timeUnit;
    this.code = code;
    this.makeVals = makeVals;
    this.logger = logger != null ? logger : LoggerFactory.getLogger(CoordinateTime2DUnionizer.class);
  }

  @Override
  public void addAll(Coordinate coord) {
    CoordinateTime2D coordT2D = (CoordinateTime2D) coord;
    for (int runIdx = 0; runIdx < coordT2D.getNruns(); runIdx++) {  // possible duplicate runtimes from different partitions
      CoordinateTimeAbstract times = coordT2D.getTimeCoordinate(runIdx);
      long runtime = coordT2D.getRuntime(runIdx);
      CoordinateTimeAbstract timesPrev = timeMap.get(runtime);
      if (timesPrev != null && !shown) {
        logger.warn("CoordinateTime2DUnionizer duplicate runtime {} from different partition \nprev={}\ncurrent={} \n{}",
                CalendarDate.of(runtime), timesPrev, times,
                Misc.stackTraceToString(Thread.currentThread().getStackTrace()));
        shown = true;
      }
      timeMap.put(runtime, times);   // later partitions will override LOOK could check how many times there are and choose larger
    }
  }

  @Override
  public Object extract(T gr) {
    throw new RuntimeException();
  }

  // set the list of runtime coordinates; add any that are not already present, and make an empty CoordinateTimeAbstract for it
  public void setRuntimeCoords(CoordinateRuntime runtimes) {
    for (int idx=0; idx<runtimes.getSize(); idx++) {
      CalendarDate cd = runtimes.getRuntimeDate(idx);
      long runtime = runtimes.getRuntime(idx);
      CoordinateTimeAbstract time = timeMap.get(runtime);
      if (time == null) {
        time = isTimeInterval ? new CoordinateTimeIntv(this.code, this.timeUnit, cd, new ArrayList<>(0), null) :
                new CoordinateTime(this.code, this.timeUnit, cd, new ArrayList<>(0), null);
        timeMap.put(runtime, time);
      }
    }
  }

  @Override
  public Coordinate makeCoordinate(List<Object> values) {

    // the set of unique runtimes, sorted
    List<Long> runtimes = new ArrayList<>();
    List<Coordinate> times = new ArrayList<>();  // the corresponding time coordinate for each runtime
    List<CoordinateTime2D.Time2D> allVals = new ArrayList<>();  // optionally all Time2D coordinates
    for (long runtime : timeMap.keySet()) {
      runtimes.add(runtime);
      CoordinateTimeAbstract time = timeMap.get(runtime);
      times.add(time);
      if (makeVals) {
        CalendarDate cd = CalendarDate.of(runtime);
        for (Object timeVal : time.getValues())
          allVals.add( isTimeInterval ? new CoordinateTime2D.Time2D(cd, null, (TimeCoord.Tinv) timeVal) : new CoordinateTime2D.Time2D(cd, (Integer) timeVal, null));
      }
    }
    Collections.sort(allVals);

    CoordinateTimeAbstract maxCoord = testOrthogonal(timeMap.values());
    if (maxCoord != null)
      return new CoordinateTime2D(code, timeUnit, allVals, new CoordinateRuntime(runtimes, timeUnit), maxCoord, times);

    List<Coordinate> regCoords = testIsRegular();
    if (regCoords != null)
      return new CoordinateTime2D(code, timeUnit, allVals, new CoordinateRuntime(runtimes, timeUnit), regCoords, times);

    return new CoordinateTime2D(code, timeUnit, allVals, new CoordinateRuntime(runtimes, timeUnit), times);
  }

  // regular means that all the times for each offset from 0Z can be made into a single time coordinate (FMRC algo)
  private List<Coordinate> testIsRegular() {

    // group time coords by offset hour
    Map<Integer, List<CoordinateTimeAbstract>> hourMap = new TreeMap<>();
    for (CoordinateTimeAbstract coord : timeMap.values()) {
      CalendarDate runDate = coord.getRefDate();
      int hour = runDate.getHourOfDay();
      List<CoordinateTimeAbstract> hg = hourMap.get(hour);
      if (hg == null) {
        hg = new ArrayList<>();
        hourMap.put(hour, hg);
      }
      hg.add(coord);
    }

    // see if each offset hour is orthogonal
    List<Coordinate> result = new ArrayList<>();
    for (int hour : hourMap.keySet()) {
      List<CoordinateTimeAbstract> hg = hourMap.get(hour);
      Coordinate maxCoord = testOrthogonal(hg);
      if (maxCoord == null) return null;
      result.add(maxCoord);
    }
    return result;
  }

  // check if the coordinate with maximum # values includes all of the time in the collection
  // if so, we can store time2D as orthogonal
  // LOOK not right I think, consider one coordinate every 6 hours, and one every 24; should not be merged.
  static public CoordinateTimeAbstract testOrthogonal(Collection<CoordinateTimeAbstract> times) {
    CoordinateTimeAbstract maxCoord = null;
    Set<Object> result = new HashSet<>(100);

    int max = 0;
    for (CoordinateTimeAbstract coord : times) {
      if (max < coord.getSize()) {
        maxCoord = coord;
        max = coord.getSize();
      }

      for (Object val : coord.getValues())
        result.add(val);
    }

    // is the set of all values the same as the component times?
    // this means we can use the "orthogonal representation" of the time2D
    int totalMax = result.size();
    return totalMax == max ? maxCoord : null;
  }


}  // Time2DUnionBuilder
