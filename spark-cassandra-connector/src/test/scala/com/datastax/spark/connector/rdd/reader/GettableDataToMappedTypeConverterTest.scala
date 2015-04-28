package com.datastax.spark.connector.rdd.reader

import scala.beans.BeanProperty

import org.apache.commons.lang3.SerializationUtils
import org.scalatest.{Matchers, FlatSpec}

import com.datastax.spark.connector.mapper.{JavaBeanColumnMapper, ColumnMapper}
import com.datastax.spark.connector.{UDTValue, CassandraRow}
import com.datastax.spark.connector.cql.{RegularColumn, PartitionKeyColumn, ColumnDef, TableDef}
import com.datastax.spark.connector.types.{MapType, ColumnType, SetType, ListType, TypeConversionException, UserDefinedType, IntType, UDTFieldDef, VarCharType}


class GettableDataToMappedTypeConverterTest extends FlatSpec with Matchers {

  val streetColumn = UDTFieldDef("street", VarCharType)
  val numberColumn = UDTFieldDef("number", IntType)
  val addressType = UserDefinedType("address", IndexedSeq(streetColumn, numberColumn))

  val loginColumn = ColumnDef("login", PartitionKeyColumn, VarCharType)
  val passwordColumn = ColumnDef("password", RegularColumn, VarCharType)
  val addressColumn = ColumnDef("address", RegularColumn, addressType)
  val addressesColumn = ColumnDef("addresses", RegularColumn, addressType)

  val userTable = new TableDef(
    keyspaceName = "test",
    tableName = "test",
    partitionKey = Seq(loginColumn),
    clusteringColumns = Seq.empty,
    regularColumns = Seq(passwordColumn, addressColumn))

  "GettableDataToMappedTypeConverter" should "be Serializable" in {
    val converter = new GettableDataToMappedTypeConverter[User](userTable, userTable.columnNames)
    SerializationUtils.roundtrip(converter)
  }

  case class User(login: String, password: String)

  it should "convert a CassandraRow to a case class object" in {
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar"))
    val converter = new GettableDataToMappedTypeConverter[User](userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.password shouldBe "bar"
  }

  it should "convert a CassandraRow to a case class object after being serialized/deserialized" in {
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar"))
    val converter =
      SerializationUtils.roundtrip(
        new GettableDataToMappedTypeConverter[User](userTable, userTable.columnNames))
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.password shouldBe "bar"
  }

  it should "convert a CassandraRow to a tuple" in {
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar"))
    val converter =
      new GettableDataToMappedTypeConverter[(String, String)](userTable, IndexedSeq("login", "password"))
    val user = converter.convert(row)
    user._1 shouldBe "foo"
    user._2 shouldBe "bar"
  }

  it should "convert a CassandraRow to a tuple in reversed order" in {
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar"))
    val converter =
      new GettableDataToMappedTypeConverter[(String, String)](userTable, IndexedSeq("password", "login"))
    val user = converter.convert(row)
    user._1 shouldBe "bar"
    user._2 shouldBe "foo"
  }

  it should "convert a CassandraRow to a tuple with a subset of columns" in {
    val row = CassandraRow.fromMap(Map("password" -> "bar"))
    val converter =
      new GettableDataToMappedTypeConverter[Tuple1[String]](userTable, IndexedSeq("password"))
    val user = converter.convert(row)
    user._1 shouldBe "bar"
  }

  it should "convert a UDTValue to a case class object" in {
    val row = UDTValue.fromMap(Map("login" -> "foo", "password" -> "bar"))
    val converter = new GettableDataToMappedTypeConverter[User](userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.password shouldBe "bar"
  }

  case class UserWithOption(login: String, password: Option[String])

  it should "convert nulls to Scala Nones" in {
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> null))
    val converter =
      new GettableDataToMappedTypeConverter[UserWithOption](
        userTable, userTable.columnNames)

    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.password shouldBe None
  }

  case class DifferentlyNamedUser(name: String, pass: String)

  it should "convert using custom column aliases" in {
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar"))
    val converter =
      new GettableDataToMappedTypeConverter[DifferentlyNamedUser](
        userTable,
        IndexedSeq("login", "password"),
        Map("name" -> "login", "pass" -> "password"))

    val user = converter.convert(row)
    user.name shouldBe "foo"
    user.pass shouldBe "bar"
  }

  class UserWithSetters {
    var login: String = null
    var password: String = null
  }

  it should "set property values with setters" in {
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar"))
    val converter = new GettableDataToMappedTypeConverter[UserWithSetters](userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.password shouldBe "bar"
  }

  case class Address(street: String, number: Int)

  it should "apply proper type conversions for columns" in {
    val address = UDTValue.fromMap(Map("street" -> "street", "number" -> "5"))  // 5 as String
    val converter = new GettableDataToMappedTypeConverter[Address](addressType, addressType.columnNames)
    val addressObj = converter.convert(address)
    addressObj.number shouldBe 5
  }

  case class UserWithAddress(login: String, password: String, address: Address)

  it should "convert a CassandraRow with a UDTValue into nested case class objects" in {
    val address = UDTValue.fromMap(Map("street" -> "street", "number" -> 5))
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar", "address" -> address))
    val converter = new GettableDataToMappedTypeConverter[UserWithAddress](
      userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.password shouldBe "bar"
    user.address.street shouldBe "street"
    user.address.number shouldBe 5
  }

  case class UserWithAddressAsTuple(login: String, password: String, address: (String, Int))

  it should "convert a CassandraRow with a UDTValue into a case class with a nested tuple" in {
    val address = UDTValue.fromMap(Map("street" -> "street", "number" -> 5))
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar", "address" -> address))
    val converter = new GettableDataToMappedTypeConverter[UserWithAddressAsTuple](
      userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.password shouldBe "bar"
    user.address._1 shouldBe "street"
    user.address._2 shouldBe 5
  }

  case class UserWithOptionalAddress(login: String, password: String, address: Option[Address])

  it should "convert a CassandraRow with an optional UDTValue" in {
    val address = UDTValue.fromMap(Map("street" -> "street", "number" -> 5))
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar", "address" -> address))
    val converter = new GettableDataToMappedTypeConverter[UserWithOptionalAddress](
      userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.password shouldBe "bar"
    user.address.isDefined shouldBe true
    user.address.get.street shouldBe "street"
    user.address.get.number shouldBe 5
  }

  case class UserWithMultipleAddresses(login: String, addresses: Vector[Address])

  def testUserWithMultipleAddresses(addressesType: ColumnType[_]) {
    val addressesColumn = ColumnDef("addresses", RegularColumn, addressesType)
    val userTable = new TableDef(
      keyspaceName = "test",
      tableName = "test",
      partitionKey = Seq(loginColumn),
      clusteringColumns = Seq.empty,
      regularColumns = Seq(addressesColumn))

    val address1 = UDTValue.fromMap(Map("street" -> "A street", "number" -> 4))
    val address2 = UDTValue.fromMap(Map("street" -> "B street", "number" -> 7))
    val row = CassandraRow.fromMap(Map("login" -> "foo", "addresses" -> List(address1, address2)))
    val converter = new GettableDataToMappedTypeConverter[UserWithMultipleAddresses](
      userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.addresses(0).street shouldBe "A street"
    user.addresses(0).number shouldBe 4
    user.addresses(1).street shouldBe "B street"
    user.addresses(1).number shouldBe 7
  }

  it should "convert a CassandraRow with a list of UDTValues" in {
    testUserWithMultipleAddresses(ListType(addressType))
  }

  it should "convert a CassandraRow with a set of UDTValues" in {
    testUserWithMultipleAddresses(SetType(addressType))
  }

  case class UserWithMultipleAddressesAsMap(login: String, addresses: Map[Int, Address])

  it should "convert a CassandraRow with a map of UDTValues" in {
    val addressesColumn = ColumnDef("addresses", RegularColumn, MapType(IntType, addressType))
    val userTable = new TableDef(
      keyspaceName = "test",
      tableName = "test",
      partitionKey = Seq(loginColumn),
      clusteringColumns = Seq.empty,
      regularColumns = Seq(addressesColumn))

    val address1 = UDTValue.fromMap(Map("street" -> "A street", "number" -> 4))
    val address2 = UDTValue.fromMap(Map("street" -> "B street", "number" -> 7))
    val addresses = Map(0 -> address1, 1 -> address2)
    val row = CassandraRow.fromMap(Map("login" -> "foo", "addresses" -> addresses))
    val converter = new GettableDataToMappedTypeConverter[UserWithMultipleAddressesAsMap](
      userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.addresses(0).street shouldBe "A street"
    user.addresses(0).number shouldBe 4
    user.addresses(1).street shouldBe "B street"
    user.addresses(1).number shouldBe 7
  }

  class UserBean {
    @BeanProperty
    var login: String = null

    @BeanProperty
    var password: String = null
  }

  it should "convert a CassandraRow to a JavaBean" in {
    implicit val cm: ColumnMapper[UserBean] = new JavaBeanColumnMapper[UserBean]
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar"))
    val converter = new GettableDataToMappedTypeConverter[UserBean](userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.password shouldBe "bar"
  }

  class AddressBean {
    @BeanProperty
    var street: String = null

    @BeanProperty
    var number: java.lang.Integer = null
  }

  class UserBeanWithAddress {
    @BeanProperty
    var login: String = null

    @BeanProperty
    var address: AddressBean = null
  }


  it should "convert a CassandraRow with UDTs to nested JavaBeans" in {
    implicit val cm: ColumnMapper[UserBeanWithAddress] = new JavaBeanColumnMapper[UserBeanWithAddress]
    val address = UDTValue.fromMap(Map("street" -> "street", "number" -> 5))
    val row = CassandraRow.fromMap(Map("login" -> "foo", "address" -> address))
    val converter = new GettableDataToMappedTypeConverter[UserBeanWithAddress](
      userTable, userTable.columnNames)
    val user = converter.convert(row)
    user.login shouldBe "foo"
    user.address.street shouldBe "street"
    user.address.number shouldBe 5
  }

  class UnknownType
  case class UserWithUnknownType(login: String, password: UnknownType)

  it should "throw a meaningful exception when a column type is not supported" in {
    val row = CassandraRow.fromMap(Map("login" -> "foo", "password" -> "bar"))
    val exception = the[IllegalArgumentException] thrownBy {
      val converter = new GettableDataToMappedTypeConverter[UserWithUnknownType](
        userTable, userTable.columnNames)
      converter.convert(row)
    }
    exception.getMessage should include("UnknownType")
  }

  it should "throw a meaningful exception when a column value fails to be converted" in {
    val address = UDTValue.fromMap(Map("street" -> "street", "number" -> "incorrect_number"))
    val exception = the[TypeConversionException] thrownBy {
      val converter = new GettableDataToMappedTypeConverter[Address](
        addressType, addressType.columnNames)
      converter.convert(address)

    }
    exception.getMessage should include("address")
    exception.getMessage should include("number")
    exception.getMessage should include("incorrect_number")
    exception.getMessage should include("Int")
  }

  it should "throw NPE with a meaningful message when a column value is null" in {
    val address = UDTValue.fromMap(Map("street" -> null, "number" -> "5"))
    val exception = the[NullPointerException] thrownBy {
      val converter = new GettableDataToMappedTypeConverter[Address](
        addressType, addressType.columnNames)
      converter.convert(address)
    }
    exception.getMessage should include("address")
    exception.getMessage should include("street")
  }

  it should "throw NPE when trying to access its targetTypeTag after serialization/deserialization" in {
    val converter = new GettableDataToMappedTypeConverter[User](userTable, userTable.columnNames)
    val deserialized = SerializationUtils.roundtrip(converter)
    a [NullPointerException] should be thrownBy deserialized.targetTypeTag
  }
}