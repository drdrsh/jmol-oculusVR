/* $RCSfile$
 * $Author: hansonr $
 *
 * Copyright (C) 2003-2005  Miguel, Jmol Development, www.jmol.org
 *
 * Contact: jmol-developers@lists.sf.net
 *
 * Copyright (C) 2009  Piero Canepa, University of Kent, UK
 *
 * Contact: pc229@kent.ac.uk or pieremanuele.canepa@gmail.com
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

package org.jmol.adapter.readers.xtal;

import org.jmol.adapter.smarter.AtomSetCollectionReader;
import org.jmol.adapter.smarter.Atom;
import org.jmol.java.BS;
import org.jmol.util.Logger;

import javajs.util.DF;
import javajs.util.M3;
import javajs.util.P3;
import javajs.util.PT;
import javajs.util.Quat;

import org.jmol.util.Tensor;
import javajs.util.V3;

import javajs.util.Lst;
import javajs.util.SB;

import java.util.Arrays;




/**
 * 
 * A reader of OUT and OUTP files for CRYSTAL
 * 
 * http://www.crystal.unito.it/
 * 
 * @author Pieremanuele Canepa, Room 104, FM Group School of Physical Sciences,
 *         Ingram Building, University of Kent, Canterbury, Kent, CT2 7NH United
 *         Kingdom, pc229@kent.ac.uk
 * 
 * @version 1.4
 * 
 * 
 *          for a specific model in the set, use
 * 
 *          load "xxx.out" n
 * 
 *          as for all readers, where n is an integer > 0
 * 
 *          for final optimized geometry use
 * 
 *          load "xxx.out" 0
 * 
 *          (that is, "read the last model") as for all readers
 * 
 *          for conventional unit cell -- input coordinates only, use
 * 
 *          load "xxx.out" filter "conventional"
 * 
 *          to NOT load vibrations, use
 * 
 *          load "xxx.out" FILTER "novibrations"
 * 
 *          to load just the input deck exactly as indicated, use
 * 
 *          load "xxx.out" FILTER "input"
 * 
 *          now allows reading of frequencies and atomic values with
 *          conventional as long as this is not an optimization.
 * 
 * 
 * 
 * 
 */

public class CrystalReader extends AtomSetCollectionReader {

  private boolean isVersion3;
  private boolean isPrimitive;
  private boolean isPolymer;
  private boolean isSlab;
  private boolean isMolecular;
  private boolean haveCharges;
  //private boolean isFreqCalc;
  private boolean inputOnly;
  private boolean isLongMode;
  private boolean getLastConventional;
  private boolean havePrimitiveMapping;
  private boolean isProperties;

  private int ac;
  private int atomIndexLast;
  private int[] atomFrag;
  private int[] primitiveToIndex;
  private float[] nuclearCharges;
  private Lst<String> vCoords;

  private Double energy;
  private P3 ptOriginShift = new P3();
  private M3 primitiveToCryst;
  private V3[] directLatticeVectors;
  private String spaceGroupName;

  @Override
  protected void initializeReader() throws Exception {
    doProcessLines = false;
    inputOnly = checkFilterKey("INPUT");
    isPrimitive = !inputOnly && !checkFilterKey("CONV");
    addVibrations &= !inputOnly; 
    getLastConventional = (!isPrimitive && desiredModelNumber == 0);
    setFractionalCoordinates(readHeader());
    asc.checkLatticeOnly = true;
  }

  @Override
  protected boolean checkLine() throws Exception {
    if (line.startsWith(" LATTICE PARAMETER")) {
      boolean isConvLattice = (line.indexOf("- CONVENTIONAL") >= 0);
      if (isConvLattice) {
        // skip if we want primitive and this is the conventional lattice
        if (isPrimitive)
          return true;
        readLatticeParams(true);
      } else if (!isPrimitive && !havePrimitiveMapping && !getLastConventional) {
        readLines(3);
        readPrimitiveMapping();
        if (setPrimitiveMapping())
          return true; // just for properties
        // no input coordinates -- continue;
      }
      readLatticeParams(true);
      if (!isPrimitive) {
        discardLinesUntilContains(" TRANSFORMATION");
        readTransformationMatrix();
        discardLinesUntilContains(" CRYSTALLOGRAPHIC");
        readLatticeParams(false);
        discardLinesUntilContains(" CRYSTALLOGRAPHIC");
        readCoordLines();
//        if (modelNumber == 1) {
//          // done here
//        } else if (!isFreqCalc) {
//          // conventional cell and now the lattice has changed.
//          // ignore? Can we convert a new primitive cell to conventional cell?
//          //continuing = false;
//          //Logger.error("Ignoring structure " + modelNumber + " due to FILTER \"conventional\"");
//          //return true;
//        }
        if (!getLastConventional) {
          if (doGetModel(++modelNumber, null)) {
            createAtomsFromCoordLines();
          } else {
            vCoords = null;
            checkLastModel();
          }
        }
      }
      return true;
    }

    if (!isPrimitive) {
      if (line.startsWith(" SHIFT OF THE ORIGIN"))
        return readShift();
      if (line.startsWith(" INPUT COORDINATES")) {
        readCoordLines(); // note, these will not be the full set of atoms, so we IGNORE VIBRATIONS
        if (inputOnly)
          continuing = false;
        return true;
      }
    }

    if (line.startsWith(" DIRECT LATTICE VECTOR"))
      return setDirect();

    if (line.indexOf("DIMENSIONALITY OF THE SYSTEM") >= 0) {
      if (line.indexOf("2") >= 0)
        isSlab = true;
      if (line.indexOf("1") >= 0)
        isPolymer = true;
      return true;
    }

//    if (line.indexOf("FRQFRQ") >= 0) {
//      isFreqCalc = true;
//      return true;
//    }

    if (addVibrations && line.startsWith(" FREQUENCIES COMPUTED ON A FRAGMENT"))
      return readFreqFragments();

    if (line.indexOf("CONSTRUCTION OF A NANOTUBE FROM A SLAB") >= 0) {
      isPolymer = true;
      isSlab = false;
      return true;
    }

    if (line.indexOf("* CLUSTER CALCULATION") >= 0) {
      isMolecular = true;
      isSlab = false;
      isPolymer = false;
      return true;
    }

    if (((isPrimitive || isMolecular) && line.startsWith(" ATOMS IN THE ASYMMETRIC UNIT"))
        || isProperties && line.startsWith("   ATOM N.AT.")) {
      if (!doGetModel(++modelNumber, null))
        return checkLastModel();
      return readAtoms();
    }
    
    if (line.startsWith(" * SUPERCELL OPTION")) {
      discardLinesUntilContains("GENERATED");
      return true;
    }

    if (!doProcessLines)
      return true;

    if (line.startsWith(" TOTAL ENERGY(")) {
      //TOTAL ENERGY CORRECTED: EXTERNAL STRESS CONTRIBUTION =    0.944E+00
      //TOTAL ENERGY(DFT)(AU)( 27) -1.2874392471314E+04     DE (AU)   -5.323E-04
      line = PT.rep(line, "( ", "(");
      String[ ] tokens = getTokens();
      energy = Double.valueOf(Double.parseDouble(tokens[2]));
      setEnergy();
      rd();
      if (line.startsWith(" ********"))
        discardLinesUntilContains("SYMMETRY ALLOWED");
      else if (line.startsWith(" TTTTTTTT"))
        discardLinesUntilContains2("PREDICTED ENERGY CHANGE", "HHHHHHH");
      return true;
    }

    if (line.startsWith(" TYPE OF CALCULATION")) {
      calculationType = line.substring(line.indexOf(":") + 1).trim();
      return true;
    }

    if (line.startsWith(" MULLIKEN POPULATION ANALYSIS"))
      return readPartialCharges();

    if (line.startsWith(" TOTAL ATOMIC CHARGES"))
      return readTotalAtomicCharges();

    if (addVibrations
        && line.contains(isVersion3 ? "EIGENVALUES (EV) OF THE MASS"
            : "EIGENVALUES (EIGV) OF THE MASS")
            || line.indexOf("LONGITUDINAL OPTICAL (LO)") >= 0) {
      createAtomsFromCoordLines();
      isLongMode = (line.indexOf("LONGITUDINAL OPTICAL (LO)") >= 0);
      return readFrequencies();
    }

    if (line.startsWith(" MAX GRADIENT"))
      return readGradient();

    if (line.startsWith(" ATOMIC SPINS SET"))
      return readData("spin", 3);

    if (line.startsWith(" TOTAL ATOMIC SPINS  :"))
      return readData("magneticMoment", 1);

    if (line.startsWith(" BORN CHARGE TENSOR."))
      return readBornChargeTensors();
    
    if (!isProperties)
      return true;
    
    /// From here on we are considering only keywords of properties output files

    if (line.startsWith(" DEFINITION OF TRACELESS"))
      return getQuadrupoleTensors();
    
    
   
    if (line.startsWith(" MULTIPOLE ANALYSIS BY ATOMS")) {
      appendLoadNote("Multipole Analysis");
      return true;
    }
    
    return true;
  }

  @Override
  protected void finalizeSubclassReader() throws Exception {
    createAtomsFromCoordLines();
    if (energy != null)
      setEnergy();
    finalizeReaderASCR();
  }

  // DIRECT LATTICE VECTORS CARTESIAN COMPONENTS (ANGSTROM)
  //          X                    Y                    Z
  //   0.290663292155E+01   0.000000000000E+00   0.460469095849E+01
  //  -0.145331646077E+01   0.251721794953E+01   0.460469095849E+01
  //  -0.145331646077E+01  -0.251721794953E+01   0.460469095849E+01
  //  
  //  or
  //  
  // DIRECT LATTICE VECTOR COMPONENTS (BOHR)
  //        11.12550    0.00000    0.00000
  //         0.00000   10.45091    0.00000
  //         0.00000    0.00000    8.90375

  private boolean setDirect() throws Exception {
    boolean isBohr = (line.indexOf("(BOHR") >= 0);
    directLatticeVectors = read3Vectors(isBohr);
//    if (debugging) {
//      addJmolScript("draw va vector {0 0 0} "
//          + Escape.eP(directLatticeVectors[0]) + " color red");
//      if (!isPolymer) {
//        addJmolScript("draw vb vector {0 0 0} "
//            + Escape.eP(directLatticeVectors[1]) + " color green");
//        if (!isSlab)
//          addJmolScript("draw vc vector {0 0 0} "
//              + Escape.eP(directLatticeVectors[2]) + " color blue");
//      }
//    }
    V3 a = new V3();
    V3 b = new V3();
    if (isPrimitive) {
      a = directLatticeVectors[0];
      b = directLatticeVectors[1];
    } else {
      if (primitiveToCryst == null)
        return true;
      M3 mp = new M3();
      mp.setColumnV(0, directLatticeVectors[0]);
      mp.setColumnV(1, directLatticeVectors[1]);
      mp.setColumnV(2, directLatticeVectors[2]);
      mp.mul(primitiveToCryst);
      a = new V3();
      b = new V3();
      mp.getColumnV(0, a);
      mp.getColumnV(1, b);
    }
    matUnitCellOrientation = Quat.getQuaternionFrame(new P3(), a, b)
    .getMatrix();
    Logger.info("oriented unit cell is in model "
        + asc.atomSetCount);
    return !isProperties;
  }

  // TRANSFORMATION MATRIX PRIMITIVE-CRYSTALLOGRAPHIC CELL
  //  1.0000  0.0000  1.0000 -1.0000  1.0000  1.0000  0.0000 -1.0000  1.0000
  
  /**
   * Read transform matrix primitive to conventional.
   * @throws Exception 
   *  
   */
  private void readTransformationMatrix() throws Exception {
    primitiveToCryst = M3.newA9(fillFloatArray(null, 0, new float[9]));
  }

  // SHIFT OF THE ORIGIN                  :    3/4    1/4      0
  
  /**
   * Read the origin shift
   * 
   * @return true
   */
  private boolean readShift() {
    String[] tokens = getTokens();
    int pt = tokens.length - 3;
    ptOriginShift.set(PT.parseFloatFraction(tokens[pt++]), PT.parseFloatFraction(tokens[pt++]),
        PT.parseFloatFraction(tokens[pt]));
    return true;
  }

  private float primitiveVolume;
  private float primitiveDensity;
  
  //0         1         2         3         4         5         6         7
  //01234567890123456789012345678901234567890123456789012345678901234567890123456789
  // PRIMITIVE CELL - CENTRING CODE 5/0 VOLUME=    30.176529 - DENSITY11.444 g/cm^3

  /**
   * Read the primitive cell volume and density.
   * Not sure why we are rounding here.
   * 
   */
  private void setPrimitiveVolumeAndDensity() {
    if (primitiveVolume != 0)
      asc.setAtomSetModelProperty("volumePrimitive", DF
          .formatDecimal(primitiveVolume, 3));
    if (primitiveDensity != 0)
      asc.setAtomSetModelProperty("densityPrimitive", DF
          .formatDecimal(primitiveDensity, 3));
  }
  
  // EEEEEEEEEE STARTING  DATE 19 03 2010 TIME 22:00:45.6
  // (title)                                                      
  //
  // CRYSTAL CALCULATION 
  // (INPUT ACCORDING TO THE INTERNATIONAL TABLES FOR X-RAY CRYSTALLOGRAPHY)
  // CRYSTAL FAMILY                       :  CUBIC       
  // CRYSTAL CLASS  (GROTH - 1921)        :  CUBIC HEXAKISOCTAHEDRAL              
  //
  // SPACE GROUP (CENTROSYMMETRIC)        :  I A 3 D         


  private boolean readHeader() throws Exception {
    //This avoid line mismatching between different version of CRYSTAL
    discardLinesUntilContains("*******************************************************************************");
    readLines(2);
    //discardLinesUntilContains("*                               CRYSTAL14");
    //discardLinesUntilContains("*                                CRYSTAL");
    
    isVersion3 = (line.indexOf("CRYSTAL03") >= 0);
    discardLinesUntilContains("EEEEEEEEEE");
    String name;
    if (rd().length() == 0) {
      name = readLines(2).trim();
    } else {
      name = line.trim();
      rd();
    }
    String type = rd().trim();
    int pt = type.indexOf("- PROPERTIES"); 
    if (pt >= 0) {
      isProperties = true;
      type = type.substring(0, pt).trim();
    }
    if (type.indexOf("EXTERNAL FILE") >= 0) {
      // GEOMETRY INPUT FROM EXTERNAL FILE (FORTRAN UNIT 34)
      type = rd().trim();
      isPolymer = (type.equals("1D - POLYMER"));
      isSlab = (type.equals("2D - SLAB"));
    } else {
      isPolymer = (type.equals("POLYMER CALCULATION"));
      isSlab = (type.equals("SLAB CALCULATION"));
    }
    asc.setCollectionName(name
        + (!isProperties && desiredModelNumber == 0 ? " (optimized)" : ""));
    asc.setInfo("symmetryType", type);
    if ((isPolymer || isSlab) && !isPrimitive) {
      Logger.error("Cannot use FILTER \"conventional\" with POLYMER or SLAB");
      isPrimitive = true;
    }
    asc.setInfo("unitCellType",
        (isPrimitive ? "primitive" : "conventional"));

    if (type.indexOf("MOLECULAR") >= 0) {
      isMolecular = doProcessLines = true;
      rd();
      asc.setInfo(
          "molecularCalculationPointGroup",
          line.substring(line.indexOf(" OR ") + 4).trim());
      return false;
    }
    spaceGroupName = "P1";
    if (!isPrimitive) {
      discardLinesUntilContains2("SPACE GROUP", "****");
      pt = line.indexOf(":"); 
      if (pt >= 0)
        spaceGroupName =  line.substring(pt + 1).trim();
    }
    doApplySymmetry = isProperties;
    return !isProperties;
  }

  // LATTICE PARAMETERS  (ANGSTROMS AND DEGREES) - CONVENTIONAL CELL
  //        A           B           C        ALPHA        BETA       GAMMA
  //     3.97500     3.97500     5.02300    90.00000    90.00000    90.00000
  //
  //or
  //
  // LATTICE PARAMETERS  (ANGSTROMS AND DEGREES) - PRIMITIVE CELL
  //       A          B          C         ALPHA     BETA     GAMMA        VOLUME
  //    3.97500    3.97500    5.02300    90.00000  90.00000  90.00000     79.366539
  //
  //or
  //
  // LATTICE PARAMETERS (ANGSTROMS AND DEGREES) - BOHR = 0.5291772083 ANGSTROM
  //   PRIMITIVE CELL - CENTRING CODE 1/0 VOLUME=    79.366539 - DENSITY 9.372 g/cm^3
  //         A              B              C           ALPHA      BETA       GAMMA 
  //     3.97500000     3.97500000     5.02300000    90.000000  90.000000  90.000000

  
  /**
   * Read the lattice parameters.
   * 
   * @param isNewSet
   * @throws Exception
   */
  private void readLatticeParams(boolean isNewSet) throws Exception {
    float f = (line.indexOf("(BOHR") >= 0 ? ANGSTROMS_PER_BOHR : 1);
    if (isNewSet)
      newAtomSet();
    if (isPolymer && !isPrimitive) {
      setUnitCell(parseFloatStr(line.substring(line.indexOf("CELL") + 4)) * f, -1, -1, 90, 90, 90);
    } else {
      while (rd().indexOf("GAMMA") < 0)
        if (line.indexOf("VOLUME=") >= 0) {
          primitiveVolume = parseFloatStr(line.substring(43));
          primitiveDensity = parseFloatStr(line.substring(66));
        }
      String[] tokens = PT.getTokens(rd());
      if (isSlab) {
        if (isPrimitive)
          setUnitCell(parseFloatStr(tokens[0]) * f, parseFloatStr(tokens[1]) * f, -1,
              parseFloatStr(tokens[3]), parseFloatStr(tokens[4]),
              parseFloatStr(tokens[5]));
        else
          setUnitCell(parseFloatStr(tokens[0]) * f, parseFloatStr(tokens[1]) * f, -1, 90, 90,
              parseFloatStr(tokens[2]));
      } else if (isPolymer) {
        setUnitCell(parseFloatStr(tokens[0]) * f, -1, -1, parseFloatStr(tokens[3]),
            parseFloatStr(tokens[4]), parseFloatStr(tokens[5]));
      } else {
        setUnitCell(parseFloatStr(tokens[0]) * f, parseFloatStr(tokens[1]) * f,
            parseFloatStr(tokens[2]) * f, parseFloatStr(tokens[3]),
            parseFloatStr(tokens[4]), parseFloatStr(tokens[5]));
      }
    }
  }

  // COORDINATES OF THE EQUIVALENT ATOMS (FRACTIONAL UNITS)
  //
  // N. ATOM EQUIV AT. N.          X                  Y                  Z
  //
  //   1   1   1   25 MN    2.50000000000E-01  3.75000000000E-01  1.25000000000E-01
  //   2   1   2   25 MN   -2.50000000000E-01  1.25000000000E-01  3.75000000000E-01
  // ...
  // 


  private Lst<String> vPrimitiveMapping;
  private void readPrimitiveMapping() throws Exception {
    if (havePrimitiveMapping)
      return;
    vPrimitiveMapping = new Lst<String>();    
    while (rd() != null && line.indexOf("NUMBER") < 0)
      vPrimitiveMapping.addLast(line);
  }
  
  /**
   * Create arrays that maps primitive atoms to conventional atoms in a 1:1
   * fashion. Creates int[] primitiveToIndex -- points to model-based atomIndex
   * 
   * @return TRUE if coordinates have been created
   * 
   * @throws Exception
   */
  private boolean setPrimitiveMapping() throws Exception {
    if (vCoords == null || vPrimitiveMapping == null || havePrimitiveMapping)
      return false;
    havePrimitiveMapping = true;
    BS bsInputAtomsIgnore = new BS();
    int n = vCoords.size();
    int[] indexToPrimitive = new int[n];
    primitiveToIndex = new int[n];
    for (int i = 0; i < n; i++)
      indexToPrimitive[i] = -1;
    int nPrim = 0;
    for (int iLine = 0; iLine < vPrimitiveMapping.size(); iLine++) {
      line = vPrimitiveMapping.get(iLine);
      if (line.indexOf(" NOT IRREDUCIBLE") >= 0) {
        // example HA_BULK_PBE_FREQ.OUT
        // we remove unnecessary atoms. This is important, because
        // these won't get properties, and we don't know exactly which
        // other atom to associate with them.
        bsInputAtomsIgnore.set(parseIntRange(line, 21, 25) - 1);
        continue;
      }

      while (rd() != null && line.indexOf("NUMBER") < 0) {
        if (line.length() < 2 || line.indexOf("ATOM") >= 0)
          continue;
        int iAtom = parseIntRange(line, 4, 8) - 1;
        if (indexToPrimitive[iAtom] < 0) {
          // no other primitive atom is mapped to a given conventional atom.
          indexToPrimitive[iAtom] = nPrim++;
        }
      }
    }
    if (bsInputAtomsIgnore.nextSetBit(0) >= 0)
      for (int i = n; --i >= 0;)
        if (bsInputAtomsIgnore.get(i))
          vCoords.remove(i);
    ac = vCoords.size();
    Logger.info(nPrim + " primitive atoms and " + ac
        + " conventionalAtoms");
    primitiveToIndex = new int[nPrim];
    for (int i = 0; i < nPrim; i++)
      primitiveToIndex[i] = -1;
    for (int i = ac; --i >= 0;) {
      int iPrim = indexToPrimitive[parseIntStr(vCoords.get(i).substring(0, 4)) - 1];
      if (iPrim >= 0)
        primitiveToIndex[iPrim] = i;
    }
    vPrimitiveMapping = null;
    return true;
  }

  /*
  ATOMS IN THE ASYMMETRIC UNIT    2 - ATOMS IN THE UNIT CELL:    4
     ATOM              X/A                 Y/B                 Z/C    
  *******************************************************************************
   1 T 282 PB    0.000000000000E+00  5.000000000000E-01  2.385000000000E-01
   2 F 282 PB    5.000000000000E-01  0.000000000000E+00 -2.385000000000E-01
   3 T   8 O     0.000000000000E+00  0.000000000000E+00  0.000000000000E+00
   4 F   8 O     5.000000000000E-01  5.000000000000E-01  0.000000000000E+00
   
   or
   
      ATOM N.AT.  SHELL    X(A)      Y(A)      Z(A)      EXAD       N.ELECT.
  *******************************************************************************
    1  282 PB    4     1.331    -0.077     1.178   1.934E-01       2.891
    2  282 PB    4    -1.331     0.077    -1.178   1.934E-01       2.891
    3  282 PB    4     1.331    -2.688    -1.178   1.934E-01       2.891
    4  282 PB    4    -1.331     2.688     1.178   1.934E-01       2.891
    5    8 O     5    -0.786     0.522     1.178   6.500E-01       9.109
    6    8 O     5     0.786    -0.522    -1.178   6.500E-01       9.109
    7    8 O     5    -0.786     2.243    -1.178   6.500E-01       9.109
    8    8 O     5     0.786    -2.243     1.178   6.500E-01       9.109

   */
  private boolean readAtoms() throws Exception {
    if (isMolecular)
      newAtomSet();
    vCoords = null;
    while (rd() != null && line.indexOf("*") < 0) {
      if (line.indexOf("X(ANGSTROM") >= 0) {
        // fullerene from slab has this.
        setFractionalCoordinates(false);
        isMolecular = true;
      }
    }
    int i = atomIndexLast;
    // I turned off normalization -- proper way to do this is to 
    // add the "packed" keyword. As it was, it was impossible to
    // load the file with its original coordinates, which in many
    // cases are VERY interesting and far better (in my opinion!)
    
    boolean doNormalizePrimitive = false;// && isPrimitive && !isMolecular && !isPolymer && !isSlab && (!doApplySymmetry || latticeCells[2] != 0);
    atomIndexLast = asc.ac;

    while (rd() != null && line.length() > 0 && line.indexOf(isPrimitive ? "*" : "=") < 0) {
      Atom atom = asc.addNewAtom();
      String[] tokens = getTokens();
      int pt = (isProperties ? 1 : 2);
      atom.elementSymbol = getElementSymbol(getAtomicNumber(tokens[pt++]));
      atom.atomName = fixAtomName(tokens[pt++]);
      if (isProperties)
        pt++; // skip SHELL
      float x = parseFloatStr(tokens[pt++]);
      float y = parseFloatStr(tokens[pt++]);
      float z = parseFloatStr(tokens[pt]);
      if (haveCharges)
        atom.partialCharge = asc.atoms[i++].partialCharge;
      if (iHaveFractionalCoordinates && !isProperties) {
        // note: this normalization is unique to this reader -- all other
        //       readers operate through symmetry application
        if (x < 0 && (isPolymer || isSlab || doNormalizePrimitive))
          x += 1;
        if (y < 0 && (isSlab || doNormalizePrimitive))
          y += 1;
        if (z < 0 && doNormalizePrimitive)
          z += 1;
      }
      setAtomCoordXYZ(atom, x, y, z);
    }
    ac = asc.ac - atomIndexLast;
    return true;
  }

  /**
   * MN33 becomes Mn33
   * 
   * @param s
   * @return fixed atom name
   */
  private static String fixAtomName(String s) {
    return (s.length() > 1 && PT.isLetter(s.charAt(1)) ? 
        s.substring(0, 1) + Character.toLowerCase(s.charAt(1)) + s.substring(2) 
        : s);
  }

  /*
   * Crystal adds 100 to the atomic number when the same atom will be described
   * with different basis sets. It also adds 200 when ECP are used.
   * 
   */
  private int getAtomicNumber(String token) {
    //   2 F 282 PB    5.000000000000E-01  0.000000000000E+00 -2.385000000000E-01
    //   3 T   8 O     0.000000000000E+00  0.000000000000E+00  0.000000000000E+00
    return parseIntStr(token) % 100;
  }

  // INPUT COORDINATES
  //
  // ATOM AT. N.              COORDINATES
  //   1  25     1.250000000000E-01  0.000000000000E+00  2.500000000000E-01
  //   2  13     0.000000000000E+00  0.000000000000E+00  0.000000000000E+00
  //   3  14     3.750000000000E-01  0.000000000000E+00  2.500000000000E-01
  //   4   8     3.444236601187E-02  4.682106125226E-02 -3.476426505505E-01
  //
  // or
  //
  //  COORDINATES IN THE CRYSTALLOGRAPHIC CELL
  //     ATOM              X/A                 Y/B                 Z/C    
  // *******************************************************************************
  //   1 T  25 MN    1.250000000000E-01  0.000000000000E+00  2.500000000000E-01
  //   2 F  25 MN    3.750000000000E-01  4.012073555523E-17 -2.500000000000E-01
  //   3 F  25 MN    2.500000000000E-01  1.250000000000E-01  0.000000000000E+00
    
  /** 
   * Read coordinates, either input or crystallographic, 
   * just saving their lines in a vector for now.
   * 
   * @throws Exception
   */
  private void readCoordLines() throws Exception {
    rd();
    rd();
    vCoords = new  Lst<String>();
    while (rd() != null && line.length() > 0)
      vCoords.addLast(line);
  }

  /**
   * Now create atoms from the coordinate lines.
   * 
   * @throws Exception
   */
  private void createAtomsFromCoordLines() throws Exception {
    if (vCoords == null)
      return;
    // here we may have deleted unnecessary input coordinates
    ac = vCoords.size();
    for (int i = 0; i < ac; i++) {
      Atom atom = asc.addNewAtom();
      String[] tokens = PT.getTokens(vCoords.get(i));
      atom.atomSerial = parseIntStr(tokens[0]);
      int atomicNumber, offset;
      if (tokens.length == 7) {
        atomicNumber = getAtomicNumber(tokens[2]);
        offset = 2;
      } else {
        atomicNumber = getAtomicNumber(tokens[1]);
        offset = 0;
      }
      float x = parseFloatStr(tokens[2 + offset]) + ptOriginShift.x;
      float y = parseFloatStr(tokens[3 + offset]) + ptOriginShift.y;
      float z = parseFloatStr(tokens[4 + offset]) + ptOriginShift.z;
      /*
       * we do not do this, because we have other ways to do it namely, "packed"
       * or "{555 555 1}" In this way, we can check those input coordinates
       * exactly
       * 
       * if (x < 0) x += 1; if (y < 0) y += 1; if (z < 0) z += 1;
       */

      setAtomCoordXYZ(atom, x, y, z);
      atom.elementSymbol = getElementSymbol(atomicNumber);
    }
    vCoords = null;
    setPrimitiveVolumeAndDensity();
  }

  private void newAtomSet() throws Exception {
    if (ac > 0 && asc.ac > 0) {
      applySymmetryAndSetTrajectory();
      asc.newAtomSet();
    }
    if (spaceGroupName != null)
      setSpaceGroupName(spaceGroupName);
    ac = 0;
  }

  private void setEnergy() {
    asc.setAtomSetEnergy("" + energy, energy.floatValue());
    asc.setCurrentModelInfo("Energy", energy);
    asc.setInfo("Energy", energy);
    asc.setAtomSetName("Energy = " + energy + " Hartree");
  }

  /*
   * MULLIKEN POPULATION ANALYSIS - NO. OF ELECTRONS 152.000000
   * 
   * ATOM Z CHARGE A.O. POPULATION
   * 
   * 1 FE 26 23.991 2.000 1.920 2.057 2.057 2.057 0.384 0.674 0.674
   */
  private boolean readPartialCharges() throws Exception {
    if (haveCharges || asc.ac == 0)
      return true;
    haveCharges = true;
    readLines(3);
    Atom[] atoms = asc.atoms;
    int i0 = asc.getLastAtomSetAtomIndex();
    int iPrim = 0;
    while (rd() != null && line.length() > 3)
      if (line.charAt(3) != ' ') {
        int iConv = getAtomIndexFromPrimitiveIndex(iPrim);
        if (iConv >= 0)
          atoms[i0 + iConv].partialCharge = parseFloatRange(line, 9, 11)
          - parseFloatRange(line, 12, 18);
        iPrim++;
      }
    return true;
  }

  private boolean readTotalAtomicCharges() throws Exception {
    SB data = new SB();
    while (rd() != null && line.indexOf("T") < 0)
      // TTTTT or SUMMED SPIN DENSITY
      data.append(line);
    String[] tokens = PT.getTokens(data.toString());
    float[] charges = new float[tokens.length];
    if (nuclearCharges == null)
      nuclearCharges = charges;
    if (asc.ac == 0)
      return true;
    Atom[] atoms = asc.atoms;
    int i0 = asc.getLastAtomSetAtomIndex();
    for (int i = 0; i < charges.length; i++) {
      int iConv = getAtomIndexFromPrimitiveIndex(i);
      if (iConv >= 0) {
        charges[i] = parseFloatStr(tokens[i]);
        atoms[i0 + iConv].partialCharge = nuclearCharges[i] - charges[i];
      }
    }
    return true;
  }

  private int getAtomIndexFromPrimitiveIndex(int iPrim) {
    return (primitiveToIndex == null ? iPrim : primitiveToIndex[iPrim]);
  }

  //
  // FREQUENCIES COMPUTED ON A FRAGMENT OF   36  ATOMS
  //    2(   8 O )     3(   8 O )     4(   8 O )    85(   8 O )    86(   8 O ) 
  //   87(   8 O )    89(   6 C )    90(   8 O )    91(   8 O )    92(   1 H ) 
  //   93(   1 H )    94(   6 C )    95(   1 H )    96(   8 O )    97(   1 H ) 
  //   98(   8 O )    99(   6 C )   100(   8 O )   101(   8 O )   102(   1 H ) 
  //  103(   1 H )   104(   6 C )   105(   1 H )   106(   8 O )   107(   8 O ) 
  //  108(   1 H )   109(   6 C )   110(   1 H )   111(   8 O )   112(   8 O ) 
  //  113(   1 H )   114(   6 C )   115(   1 H )   116(   8 O )   117(   8 O ) 
  //  118(   1 H ) 
  // 

  /**
   * Select only specific atoms for frequency generation.
   * (See freq_6for_001.out)
   * 
   * @return true
   * @throws Exception 
   * 
   */
  private boolean readFreqFragments() throws Exception {
    int numAtomsFrag = parseIntRange(line, 39, 44);
    if (numAtomsFrag < 0)
      return true;
    atomFrag = new int[numAtomsFrag];
    String Sfrag = "";
    while (rd() != null && line.indexOf("(") >= 0)
      Sfrag += line;
    Sfrag = PT.rep(Sfrag, "(", " ");
    Sfrag = PT.rep(Sfrag, ")", " ");
    String[] tokens = PT.getTokens(Sfrag);
    for (int i = 0, pos = 0; i < numAtomsFrag; i++, pos += 3)
      atomFrag[i] = getAtomIndexFromPrimitiveIndex(parseIntStr(tokens[pos]) - 1);

    Arrays.sort(atomFrag); // the frequency module needs these sorted

    // note: atomFrag[i] will be -1 if this atom is being ignored due to FILTER "conventional"

    return true;
  }

  // not all crystal calculations include intensities values
  // this feature is activated when the keyword INTENS is on the input
  //
  // transverse:
  //
  // 0         1         2         3         4         5         6         7         
  // 01234567890123456789012345678901234567890123456789012345678901234567890123456789
  //     MODES          EV           FREQUENCIES     IRREP  IR   INTENS    RAMAN
  //                   (AU)      (CM**-1)     (THZ)             (KM/MOL)
  //     1-   1   -0.00004031    -32.6352   -0.9784  (A2 )   I (     0.00)   A
  //     2-   2   -0.00003920    -32.1842   -0.9649  (B2 )   A (  6718.50)   A
  //     3-   3   -0.00000027     -2.6678   -0.0800  (A1 )   A (     3.62)   A
  //
  // Longitudinal:
  //
  // 0         1         2         3         4         5         6         7         
  // 01234567890123456789012345678901234567890123456789012345678901234567890123456789
  //     MODES         EIGV          FREQUENCIES    IRREP IR INTENS       SHIFTS
  //              (HARTREE**2)   (CM**-1)     (THZ)       (KM/MOL)  (CM**-1)   (THZ)
  //     4-   6    0.2370E-06    106.8457    3.2032 (F1U)     40.2    7.382   0.2213
  //    16-  18    0.4250E-06    143.0817    4.2895 (F1U)    181.4   14.234   0.4267
  //    31-  33    0.5848E-06    167.8338    5.0315 (F1U)     24.5    1.250   0.0375
  //    41-  43    0.9004E-06    208.2551    6.2433 (F1U)    244.7   10.821   0.3244

  private boolean readFrequencies() throws Exception {
    energy = null; // don't set energy for these models
    discardLinesUntilContains("MODES");
    // This line is always there
    boolean haveIntensities = (line.indexOf("INTENS") >= 0);
    rd();
    Lst<String[]> vData = new  Lst<String[]>();
    int freqAtomCount = ac;
    while (rd() != null && line.length() > 0) {
      int i0 = parseIntRange(line, 1, 5);
      int i1 = parseIntRange(line, 6, 10);
      String irrep = (isLongMode ? line.substring(48, 51) : line.substring(49,
          52)).trim();
      String intens = (!haveIntensities ? "not available" : (isLongMode ? line
          .substring(53, 61) : line.substring(59, 69).replace(')', ' ')).trim());

      String irActivity = (isLongMode ? "A" : line.substring(55, 58).trim());
      String ramanActivity = (isLongMode ? "I" : line.substring(71, 73).trim());

      String[] data = new String[] { irrep, intens, irActivity, ramanActivity };
      for (int i = i0; i <= i1; i++)
        vData.addLast(data);
    }
    discardLinesUntilContains(isLongMode ? "LO MODES FOR IRREP"
        : isVersion3 ? "THE CORRESPONDING MODES"
            : "NORMAL MODES NORMALIZED TO CLASSICAL AMPLITUDES");
    rd();
    int lastAtomCount = -1;
    while (rd() != null && line.startsWith(" FREQ(CM**-1)")) {
      String[] tokens = PT.getTokens(line.substring(15));
      float[] frequencies = new float[tokens.length];
      int frequencyCount = frequencies.length;
      for (int i = 0; i < frequencyCount; i++) {
        frequencies[i] = parseFloatStr(tokens[i]);
        if (debugging)
          Logger.debug((vibrationNumber + i) + " frequency=" + frequencies[i]);
      }
      boolean[] ignore = new boolean[frequencyCount];
      int iAtom0 = 0;
      int nData = vData.size();
      for (int i = 0; i < frequencyCount; i++) {
        tokens = vData.get(vibrationNumber % nData);
        ignore[i] = (!doGetVibration(++vibrationNumber) || tokens == null);
        if (ignore[i])
          continue;
        applySymmetryAndSetTrajectory();
        lastAtomCount = cloneLastAtomSet(ac, null);
        if (i == 0)
          iAtom0 = asc.getLastAtomSetAtomIndex();
        setFreqValue(frequencies[i], tokens);
      }
      rd();
      fillFrequencyData(iAtom0, freqAtomCount, lastAtomCount, ignore, false,
          14, 10, atomFrag, 0);
      rd();
    }
    return true;
  }

  private void setFreqValue(float freq, String[] data) {
    String activity = "IR: " + data[2] + ", Ram.: " + data[3];
    asc.setAtomSetFrequency(null, activity, "" + freq, null);
    asc.setAtomSetModelProperty("IRintensity", data[1] + " km/Mole");
    asc.setAtomSetModelProperty("vibrationalSymmetry", data[0]);
    asc.setAtomSetModelProperty("IRactivity", data[2]);
    asc.setAtomSetModelProperty("Ramanactivity", data[3]);
    asc.setAtomSetName((isLongMode ? "LO " : "") + data[0] + " "
        + DF.formatDecimal(freq, 2) + " cm-1 ("
        + DF.formatDecimal(PT.fVal(data[1]), 0)
        + " km/Mole), " + activity);
  }

  // MAX GRADIENT      0.000967  THRESHOLD             
  // RMS GRADIENT      0.000967  THRESHOLD              
  // MAX DISPLAC.      0.005733  THRESHOLD             
  // RMS DISPLAC.      0.005733  THRESHOLD

  /**
   * Read minimization measures
   * 
   * @return true
   * @throws Exception
   */
  private boolean readGradient() throws Exception {
    String key = null;
    while (line != null) {
      String[] tokens = getTokens();
      if (line.indexOf("MAX GRAD") >= 0)
        key = "maxGradient";
      else if (line.indexOf("RMS GRAD") >= 0)
        key = "rmsGradient";
      else if (line.indexOf("MAX DISP") >= 0)
        key = "maxDisplacement";
      else if (line.indexOf("RMS DISP") >= 0)
        key = "rmsDisplacement";
      else
        break;
      if (asc.ac > 0)
        asc.setAtomSetModelProperty(key, tokens[2]);
      rd();
    }
    return true;
  }

  // SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS
  // ATOMIC SPINS SET TO (ATOM, AT. N., SPIN)
  //   1  26-1   2   8 0   3   8 0   4   8 0   5  26 1   6  26 1   7   8 0   8   8 0
  //   9   8 0  10  26-1  11  26-1  12   8 0  13   8 0  14   8 0  15  26 1  16  26 1
  //  17   8 0  18   8 0  19   8 0  20  26-1  21  26-1  22   8 0  23   8 0  24   8 0
  //  25  26 1  26  26 1  27   8 0  28   8 0  29   8 0  30  26-1
  // ALPHA-BETA ELECTRONS LOCKED TO   0 FOR  50 SCF CYCLES
  //
  // or (for magnetic moments)
  //
  // TOTAL ATOMIC SPINS  :
  //   5.0000000  -5.0000000  -5.0000000   5.0000000   0.0000000   0.0000000
  //   0.0000000   0.0000000   0.0000000   0.0000000
  // TTTTTTTTTTTTTTTTTTTTTTTTTTTTTT MOQGAD      TELAPSE      233.11 TCPU      154.98
  
  /**
   * For spin and magnetic moment data, read the data block 
   * and save it as property_spin or propert_magneticMoment.
   * 
   * @param name
   * @param nfields 
   * @return true
   * @throws Exception
   */
  private boolean readData(String name, int nfields) throws Exception {
    createAtomsFromCoordLines();
    float[] f = new float[ac];
    for (int i = 0; i < ac; i++)
      f[i] = 0;
    String data = "";
    while (rd() != null && (line.length() < 4 || PT.isDigit(line.charAt(3))))
      data += line;
    data = PT.rep(data, "-", " -");
    String[] tokens = PT.getTokens(data);
    for (int i = 0, pt = nfields - 1; i < ac; i++, pt += nfields) {
      int iConv = getAtomIndexFromPrimitiveIndex(i);
      if (iConv >= 0)
        f[iConv] = parseFloatStr(tokens[pt]);
    }
    asc.setAtomProperties(name, f, -1, false);
    return true;
  }

  
  // DEFINITION OF TRACELESS QUADRUPOLE TENSORS:
  // 
  // (3XX-RR)/2=(2,2)/4-(2,0)/2
  // (3YY-RR)/2=-(2,2)/4-(2,0)/2
  // (3ZZ-RR)/2=(2,0)
  // 3XY/2=(2,-2)/4     3XZ/2=(2,1)/2     3YZ/2=(2,-1)/2
  // 
  // *** ATOM N.     1 (Z=282) PB
  //
  // TENSOR IN PRINCIPAL AXIS SYSTEM
  // AA  6.265724E-01 BB -2.651563E-01 CC -3.614161E-01
  //
  // *** ATOM N.     2 (Z=282) PB
  //
  // TENSOR IN PRINCIPAL AXIS SYSTEM
  // AA  6.265724E-01 BB -2.651563E-01 CC -3.614161E-01
  //...
  // TTTTTTTTTTTTTTTTTTTTTTTTTTTTTT POLI        TELAPSE        0.05 TCPU        0.04

  private boolean getQuadrupoleTensors() throws Exception {
     readLines(6);
     Atom[] atoms = asc.atoms;
     while (rd() != null  && line.startsWith(" *** ATOM")) {
       String[] tokens = getTokens();
       int index = parseIntStr(tokens[3]) - 1;
       tokens = PT.getTokens(readLines(3));
       V3[] vectors = new V3[3];
       for (int i = 0; i < 3; i++) {
         vectors[i] = V3.newV(directLatticeVectors[i]);
         vectors[i].normalize();
       }
      atoms[index].addTensor(new Tensor().setFromEigenVectors(vectors, 
           new float[] {parseFloatStr(tokens[1]), 
           parseFloatStr(tokens[3]), 
           parseFloatStr(tokens[5]) }, "quadrupole", atoms[index].atomName, null), null, false);
       rd();
     }
     appendLoadNote("Ellipsoids set \"quadrupole\": Quadrupole tensors");
     return true;
   }

  // BORN CHARGE TENSOR. (DINAMIC CHARGE = 1/3 * TRACE)
  //
  // ATOM   2 O  DYNAMIC CHARGE    -1.274519
  //
  //              1           2           3
  //   1    -1.3467E+00 -1.6358E-02 -6.0557E-01
  //   2     1.3223E-01 -1.3781E+00 -2.1223E-02
  //   3    -1.5921E-01 -1.4427E-01 -1.0988E+00
  // ...

  private boolean readBornChargeTensors() throws Exception {
    createAtomsFromCoordLines();
    rd();
    Atom[] atoms = asc.atoms;
    while (rd().startsWith(" ATOM")) {
      int index = parseIntAt(line, 5) - 1;
      Atom atom = atoms[index];
      readLines(2);
      double[][] a = new double[3][3];
      for (int i = 0; i < 3; i++) {
        String[] tokens = PT.getTokens(rd());
        for (int j = 0; j < 3; j++)
          a[i][j] = parseFloatStr(tokens[j + 1]);
      }
      atom.addTensor(new Tensor().setFromAsymmetricTensor(a, "charge", atom.elementSymbol + (index + 1)), null, false);
      rd();
    }
    appendLoadNote("Ellipsoids set \"charge\": Born charge tensors");
    return false;
  }
}
